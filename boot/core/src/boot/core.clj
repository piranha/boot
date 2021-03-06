(ns boot.core
  "The boot core API."
  (:require
    [clojure.java.io             :as io]
    [clojure.set                 :as set]
    [clojure.walk                :as walk]
    [clojure.repl                :as repl]
    [clojure.string              :as string]
    [boot.pod                    :as pod]
    [boot.git                    :as git]
    [boot.cli                    :as cli2]
    [boot.file                   :as file]
    [boot.tmpregistry            :as tmp]
    [boot.tmpdir                 :as tmpd]
    [boot.util                   :as util]
    [boot.from.clojure.tools.cli :as cli])
  (:import
    [java.io File]
    [java.net URLClassLoader URL]
    [java.lang.management ManagementFactory]
    [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(declare watch-dirs sync! on-env! get-env set-env! tmpfile tmpdir ls)

(declare ^{:dynamic true :doc "The running version of boot app."}      *app-version*)
(declare ^{:dynamic true :doc "The running version of boot core."}     *boot-version*)
(declare ^{:dynamic true :doc "Command line options for boot itself."} *boot-opts*)
(declare ^{:dynamic true :doc "Count of warnings during build."}       *warnings*)

;; Internal helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private tmpregistry       (atom nil))
(def ^:private cleanup-fns       (atom []))
(def ^:private boot-env          (atom nil))
(def ^:private tempdirs          (atom #{}))
(def ^:private src-watcher       (atom (constantly nil)))

(def ^:private masks
  {:user     {:user true}
   :input    {:input true}
   :output   {:output true}
   :cache    {:input nil :output nil}
   :asset    {:input nil :output true}
   :source   {:input true :output nil}
   :resource {:input true :output true}})

(defn- get-dirs [this masks+]
  (let [dirs        (:dirs this)
        has-mask?   #(= %1 (select-keys %2 (keys %1)))
        filter-keys #(->> %1 (filter (partial has-mask? %2)))]
    (->> masks+ (map masks) (apply merge) (filter-keys dirs) (map tmpfile) set)))

(defn- get-add-dir [this masks+]
  (let [user?  (contains? masks+ :user)
        u-dirs (when-not user? (get-dirs this #{:user}))]
    (-> this (get-dirs masks+) (set/difference u-dirs) first)))

(defn- get-files [this masks+]
  (let [dirs (get-dirs this masks+)]
    (->> this ls (filter (comp dirs tmpdir)) set)))

(defn- temp-dir*
  [key]
  (tmp/mkdir! @tmpregistry key))

(def ^:private new-fileset
  (memoize
    (fn []
      (boot.tmpdir.TmpFileSet. @tempdirs {} (temp-dir* ::blob)))))

(defn- temp-dir**
  [key & masks+]
  (let [k (or key (keyword "boot.core" (str (gensym))))
        m (->> masks+ (map masks) (apply merge))
        in-fileset? (or (:input m) (:output m))]
    (util/with-let [d (temp-dir* k)]
      (when in-fileset?
        (let [t (tmpd/map->TmpDir (assoc m :dir d))]
          (swap! tempdirs conj t)
          (when (:input t)
            (set-env! :directories #(conj % (.getPath (:dir t))))))))))

(defn- add-user-asset    [fileset dir] (tmpd/add fileset (get-add-dir fileset #{:user :asset}) dir))
(defn- add-user-source   [fileset dir] (tmpd/add fileset (get-add-dir fileset #{:user :source}) dir))
(defn- add-user-resource [fileset dir] (tmpd/add fileset (get-add-dir fileset #{:user :resource}) dir))

(defn- user-temp-dirs     [] (get-dirs {:dirs @tempdirs} #{:user}))
(defn- user-asset-dirs    [] (get-dirs {:dirs @tempdirs} #{:user :asset}))
(defn- user-source-dirs   [] (get-dirs {:dirs @tempdirs} #{:user :source}))
(defn- user-resource-dirs [] (get-dirs {:dirs @tempdirs} #{:user :resource}))
(defn- non-user-temp-dirs [] (-> (apply set/union (map :dir @tempdirs))
                                 (set/difference (user-temp-dirs))))

(defn- sync-user-dirs!
  []
  (doseq [[k d] {:source-paths   (user-source-dirs)
                 :resource-paths (user-resource-dirs)
                 :asset-paths    (user-asset-dirs)}]
    (when-let [s (seq (get-env k))]
      (binding [file/*hard-link* false]
        (apply file/sync! :time (first d) s)))))

(defn- set-fake-class-path!
  "Sets the fake.class.path system property to reflect all JAR files on the
  pod class path plus the :source-paths and :resource-paths. Note that these
  directories are not actually on the class path (this is why it's the fake
  class path). This property is a workaround for IDEs and tools that expect
  the full class path to be determined by the java.class.path property.
  
  Also sets the boot.class.path system property which is the same as above
  except that the actual class path directories are used instead of the user's
  project directories. This property can be used to configure Java tools that
  would otherwise be looking at java.class.path expecting it to have the full
  class path (the javac task uses it, for example, to configure the Java com-
  piler class)."
  []
  (let [user-dirs  (->> (get-env)
                        ((juxt :source-paths :resource-paths))
                        (apply concat)
                        (map #(.getAbsolutePath (io/file %))))
        paths      (->> (pod/get-classpath) (map #(.getPath (URL. %))))
        dir?       (comp (memfn isDirectory) io/file)
        fake-paths (->> paths (remove dir?) (concat user-dirs))
        separated  (partial string/join (System/getProperty "path.separator"))]
    (System/setProperty "boot.class.path" (separated paths))
    (System/setProperty "fake.class.path" (separated fake-paths))))

(defn- set-user-dirs!
  "Resets the file watchers that sync the project directories to their
  corresponding temp dirs, reflecting any changes to :source-paths, etc."
  []
  (@src-watcher)
  (->> [:source-paths :resource-paths :asset-paths]
    (map get-env)
    (apply set/union)
    (watch-dirs (fn [_] (sync-user-dirs!)))
    (reset! src-watcher))
  (set-fake-class-path!)
  (sync-user-dirs!))

(defn- do-cleanup!
  "Cleanup handler. This is run after the build process is complete. Tasks can
  register cleanup callbacks via the cleanup macro below."
  []
  (doseq [f @cleanup-fns] (util/guard (f)))
  (reset! cleanup-fns []))

(defn- printable-readable?
  "If the form can be round-tripped through pr-str -> read-string -> pr-str
  then form is returned, otherwise nil."
  [form]
  (or (nil? form)
      (false? form)
      (try (read-string (pr-str form)) (catch Throwable _))))

(defn- rm-clojure-dep
  "Remove the org.clojure/clojure dependency (if one exists) from a Maven
  dependencies vector."
  [deps]
  (vec (remove (comp (partial = 'org.clojure/clojure) first) deps)))

(defn load-data-readers!
  "Refresh *data-readers* with readers from newly acquired dependencies."
  []
  (#'clojure.core/load-data-readers)
  (set! *data-readers* (.getRawRoot #'*data-readers*)))

(defn- add-dependencies!
  "Add Maven dependencies to the classpath, fetching them if necessary."
  [old new env]
  (->> new rm-clojure-dep (assoc env :dependencies) pod/add-dependencies)
  (set-fake-class-path!)
  new)

(defn- add-directories!
  "Add URLs (directories or jar files) to the classpath."
  [dirs]
  (set-fake-class-path!)
  (doseq [dir dirs] (pod/add-classpath dir)))

(defn- configure!*
  "Performs side-effects associated with changes to the env atom. Boot adds this
  function as a watcher on it."
  [old new]
  (doseq [k (set/union (set (keys old)) (set (keys new)))]
    (let [o (get old k ::noval)
          n (get new k ::noval)]
      (if (not= o n) (on-env! k o n new)))))

(defn- add-wagon!
  "Adds a maven wagon dependency to the worker pod and initializes it with an
  optional map of scheme handlers."
  ([maven-coord scheme-map]
   (add-wagon! nil [maven-coord] (get-env) scheme-map))
  ([old new env]
   (add-wagon! old new env nil))
  ([old new env scheme-map]
   (doseq [maven-coord new]
     (pod/with-call-worker
       (boot.aether/add-wagon ~env ~maven-coord ~scheme-map)))
   new))

(defn- order-set-env-keys
  "Ensures that :dependencies are processed last, because changes to other
  keys affect dependency resolution."
  [kvs]
  (let [dk :dependencies]
    (->> kvs (sort-by first #(cond (= %1 dk) 1 (= %2 dk) -1 :else 0)))))

(defn- parse-task-opts
  "Given and argv and a tools.cli type argspec spec, returns a vector of the
  parsed option map and a list of remaining non-option arguments. This is how
  tasks in a pipeline created on the cli are separated into individual tasks
  and task options."
  [argv spec]
  (loop [opts [] [car & cdr :as argv] argv]
    (if-not car
      [opts argv]
      (let [opts* (conj opts car)
            parsd (cli/parse-opts opts* spec :in-order true)]
        (if (seq (:arguments parsd)) [opts argv] (recur opts* cdr))))))

(defmacro ^:private daemon
  "Evaluate the body in a new daemon thread."
  [& body]
  `(doto (Thread. (fn [] ~@body)) (.setDaemon true) .start))

;; Tempdir and Fileset API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn temp-dir!
  "Creates a boot-managed temporary directory, returning a java.io.File."
  []
  (temp-dir** nil :cache))

;; TmpFile API

(defn tmppath
  "Returns the tmpfile's path relative to the fileset root."
  [tmpfile]
  (tmpd/path tmpfile))

(defn tmpdir
  "Returns the temporary directory containing the tmpfile."
  [tmpfile]
  (tmpd/dir tmpfile))

(defn tmpfile
  "Returns the java.io.File object for the tmpfile."
  [tmpfile]
  (tmpd/file tmpfile))

(defn fileset-diff
  "Returns a new fileset containing files that were added or modified. Removed
  files are not considered."
  [before after]
  (if-not before after (tmpd/diff before after)))

;; TmpFileSet API

(defn user-dirs
  "Get a list of directories containing files that originated in the project's
  source, resource, or asset paths."
  [fileset]
  (get-dirs fileset #{:user}))

(defn input-dirs
  "Get a list of directories containing files with input roles."
  [fileset]
  (get-dirs fileset #{:input}))

(defn output-dirs
  [fileset]
  (get-dirs fileset #{:output}))

(defn user-files
  "Get a list of TmpFile objects corresponding to files that originated in
  the project's source, resource, or asset paths."
  [fileset]
  (get-files fileset #{:user}))

(defn input-files
  "Get a list of TmpFile objects corresponding to files with input role."
  [fileset]
  (get-files fileset #{:input}))

(defn output-files
  "Get a list of TmpFile objects corresponding to files with output role."
  [fileset]
  (get-files fileset #{:output}))

(defn ls
  "Get a list of TmpFile objects for all files in the fileset."
  [fileset]
  (tmpd/ls fileset))

(defn commit!
  "Make the underlying temp directories correspond to the immutable fileset
  tree structure."
  [fileset]
  (tmpd/commit! fileset))

(defn rm
  "Removes files from the fileset tree, returning a new fileset object. This
  does not affect the underlying filesystem in any way."
  [fileset files]
  (tmpd/rm fileset files))

(defn- non-user-dir-for
  "Given a fileset and a directory d in that fileset's set of underlying temp
  directories, returns a directory of the same type (ie. source, resource, or
  asset) but not a user dir (ie. not one of the directories that is synced to
  project dirs)."
  [fileset d]
  (let [u (user-dirs fileset)]
    (or (and (not (u d)) d)
        (->> (cond ((get-dirs fileset #{:asset}) d) #{:asset}
                   ((get-dirs fileset #{:source}) d) #{:source}
                   ((get-dirs fileset #{:resource}) d) #{:resource})
             (get-add-dir fileset)))))

(defn cp
  "Given a fileset and a dest-tmpfile from that fileset, overwrites the dest
  tmpfile with the contents of the java.io.File src-file."
  [fileset ^File src-file dest-tmpfile]
  (->> (tmpdir dest-tmpfile)
       (non-user-dir-for fileset)
       (assoc dest-tmpfile :dir)
       (tmpd/cp fileset src-file)))

(defn add-asset
  "Add the contents of the java.io.File dir to the fileset's assets."
  [fileset ^File dir]
  (tmpd/add fileset (get-add-dir fileset #{:asset}) dir))

(defn add-source
  "Add the contents of the java.io.File dir to the fileset's sources."
  [fileset ^File dir]
  (tmpd/add fileset (get-add-dir fileset #{:source}) dir))

(defn add-resource
  "Add the contents of the java.io.File dir to the fileset's resources."
  [fileset ^File dir] (tmpd/add fileset (get-add-dir fileset #{:resource}) dir))

;; Tempdir helpers

(defn empty-dir!
  "For each directory in dirs, recursively deletes all files and subdirectories.
  The directories in dirs themselves are not deleted."
  [& dirs]
  (apply file/empty-dir! dirs))

(defn sync!
  "Given a dest directory and one or more srcs directories, overlays srcs on
  dest, removing files in dest that are not in srcs. Uses file modification
  timestamps to decide which version of files to emit to dest. Uses hardlinks
  instead of copying file contents. File modification times are preserved."
  [dest & srcs]
  (apply file/sync! :time dest srcs))

;; Boot Environment ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn watch-dirs
  "Watches dirs for changes and calls callback with set of changed files
  when file(s) in these directories are modified. Returns a thunk which
  will stop the watcher.

  The watcher uses the somewhat quirky native filesystem event APIs. A
  debounce option is provided (in ms, default 10) which can be used to
  tune the watcher sensitivity."
  [callback dirs & {:keys [debounce]}]
  (if (empty? dirs)
    (constantly true)
    (do (pod/require-in @pod/worker-pod "boot.watcher")
        (let [q       (LinkedBlockingQueue.)
              watcher (apply file/watcher! :time dirs)
              paths   (into-array String dirs)
              k       (.invoke @pod/worker-pod "boot.watcher/make-watcher" q paths)]
          (daemon
            (loop [ret (util/guard [(.take q)])]
              (when ret
                (if-let [more (.poll q (or debounce 10) TimeUnit/MILLISECONDS)]
                  (recur (conj ret more))
                  (let [changed (watcher)]
                    (when-not (empty? changed) (callback changed))
                    (recur (util/guard [(.take q)])))))))
          #(.invoke @pod/worker-pod "boot.watcher/stop-watcher" k)))))

(defn init!
  "Initialize the boot environment. This is normally run once by boot at
  startup. There should be no need to call this function directly."
  []
  (->> (io/file ".boot" "tmp") tmp/registry tmp/init! (reset! tmpregistry))
  (doto boot-env
    (reset! {:dependencies   []
             :directories    #{}
             :source-paths   #{}
             :resource-paths #{}
             :asset-paths    #{}
             :target-path    "target"
             :repositories   [["clojars"       "http://clojars.org/repo/"]
                              ["maven-central" "http://repo1.maven.org/maven2/"]]})
    (add-watch ::boot #(configure!* %3 %4)))
  (set-fake-class-path!)
  (temp-dir** nil :asset)
  (temp-dir** nil :source)
  (temp-dir** nil :resource)
  (temp-dir** nil :user :asset)
  (temp-dir** nil :user :source)
  (temp-dir** nil :user :resource)
  (pod/add-shutdown-hook! do-cleanup!))

(defmulti on-env!
  "Event handler called when the boot atom is modified. This handler is for
  performing side-effects associated with maintaining the application state in
  the boot atom. For example, when `:src-paths` is modified the handler adds
  the new directories to the classpath."
  (fn [key old-value new-value env] key) :default ::default)

(defmethod on-env! ::default       [key old new env] nil)
(defmethod on-env! :directories    [key old new env] (add-directories! new))
(defmethod on-env! :source-paths   [key old new env] (set-user-dirs!))
(defmethod on-env! :resource-paths [key old new env] (set-user-dirs!))
(defmethod on-env! :asset-paths    [key old new env] (set-user-dirs!))

(defmulti merge-env!
  "This function is used to modify how new values are merged into the boot atom
  when `set-env!` is called. This function's result will become the new value
  associated with the given `key` in the boot atom."
  (fn [key old-value new-value env] key) :default ::default)

(defmethod merge-env! ::default     [key old new env] new)
(defmethod merge-env! :directories  [key old new env] (set/union old new))
(defmethod merge-env! :wagons       [key old new env] (add-wagon! old new env))
(defmethod merge-env! :dependencies [key old new env] (add-dependencies! old new env))

(defn get-env
  "Returns the value associated with the key `k` in the boot environment, or
  `not-found` if the environment doesn't contain key `k` and `not-found` was
  given. Calling this function with no arguments returns the environment map."
  [& [k not-found]]
  (if k (get @boot-env k not-found) @boot-env))

(defn set-env!
  "Update the boot environment atom `this` with the given key-value pairs given
  in `kvs`. See also `on-env!` and `merge-env!`. The values in the env map must
  be both printable by the Clojure printer and readable by its reader. If the
  value for a key is a function, that function will be applied to the current
  value of that key and the result will become the new value (similar to how
  clojure.core/update-in works."
  [& kvs]
  (doseq [[k v] (order-set-env-keys (partition 2 kvs))]
    (let [v (if-not (fn? v) v (v (get-env k)))]
      (assert (printable-readable? v)
        (format "value not readable by Clojure reader\n%s => %s" (pr-str k) (pr-str v)))
      (swap! boot-env update-in [k] (partial merge-env! k) v @boot-env))))

;; Defining Tasks ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro deftask
  "Define a boot task."
  [sym & forms]
  `(cli2/defclifn ~(vary-meta sym assoc ::task true) ~@forms))

;; Boot Lifecycle ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro cleanup
  "Evaluate body after tasks have been run. This macro is meant to be called
  from inside a task definition, and is provided as a means to shutdown or
  clean up persistent resources created by the task (eg. threads, files, etc.)"
  [& body]
  `(swap! @#'boot.core/cleanup-fns conj (fn [] ~@body)))

(defn reset-fileset
  "Updates the user directories in the fileset with the latest project files,
  returning a new immutable fileset. When called with no args returns a new
  fileset containing only the latest project files."
  [& [fileset]]
  (sync-user-dirs!)
  (-> (if-not fileset
        (new-fileset)
        (rm fileset (user-files fileset)))
      (add-user-asset (first (user-asset-dirs)))
      (add-user-source (first (user-source-dirs)))
      (add-user-resource (first (user-resource-dirs)))))

(defn reset-build!
  "Resets mutable build state to default values. This includes such things as
  warning counters etc., state that is relevant to a single build cycle. This
  function should be called before each build iteration."
  []
  (reset! *warnings* 0))

(defn- construct-tasks
  "FIXME: document"
  [& argv]
  (loop [ret [] [op-str & args] argv]
    (if-not op-str
      (apply comp (filter fn? ret))
      (let [op (-> op-str symbol resolve)]
        (when-not (and op (:boot.core/task (meta op)))
          (throw (IllegalArgumentException. (format "No such task (%s)" op-str))))
        (let [spec   (:argspec (meta op))
              parsed (cli/parse-opts args spec :in-order true)]
          (when (seq (:errors parsed))
            (throw (IllegalArgumentException. (string/join "\n" (:errors parsed)))))
          (let [[opts argv] (parse-task-opts args spec)]
            (recur (conj ret (apply (var-get op) opts)) argv)))))))

(defn- run-tasks
  "FIXME: document"
  [task-stack]
  (let [sync! #(let [tgt (get-env :target-path)]
                 (binding [file/*hard-link* false]
                   (apply file/sync! :time tgt (output-dirs %)))
                 (file/delete-empty-subdirs! tgt)
                 (sync-user-dirs!))]
    (binding [*warnings* (atom 0)]
      (reset-build!)
      ((task-stack #(do (sync! %) %)) (commit! (reset-fileset))))))

(defmacro boot
  "Builds the project as if `argv` was given on the command line."
  [& argv]
  (let [->list #(cond (seq? %) % (vector? %) (seq %) :else (list %))
        ->app  (fn [xs] `(apply comp (filter fn? [~@xs])))]
    `(try @(future
             (util/with-let [_# nil]
               (#'run-tasks
                 ~(if (every? string? argv)
                    `(apply #'construct-tasks [~@argv])
                    (->app (map ->list argv))))))
          (finally (#'do-cleanup!)))))

;; Low-Level Tasks, Helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-pre-wrap
  [bind & body]
  `(fn [next-task#]
     (fn [fileset#]
       (assert (tmpd/tmpfileset? fileset#)
               "argument to task handler not a fileset")
       (let [~bind fileset#
             result# (do ~@body)]
         (assert (tmpd/tmpfileset? result#)
                 "task handler must return a fileset")
         (next-task# result#)))))

(defmacro with-post-wrap
  [bind & body]
  `(fn [next-task#]
     (fn [fileset#]
       (assert (tmpd/tmpfileset? fileset#)
               "argument to task handler not a fileset")
       (let [result# (next-task# fileset#)
             ~bind   result#]
         (assert (tmpd/tmpfileset? result#)
                 "task handler must return a fileset")
         (binding [tmpd/*locked* true] ~@body)
         result#))))

;; Task Configuration Macros ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro replace-task!
  "Given a number of binding form and function pairs, this macro alters the
  root bindings of task vars, replacing their values with the given functions.

  Example:

  (replace-task!
    [r repl] (fn [& xs] (apply r :port 12345 xs))
    [j jar]  (fn [& xs] (apply j :manifest {\"howdy\" \"world\"} xs)))"
  [& replacements]
  `(do ~@(for [[[bind task] expr] (partition 2 replacements)]
           `(alter-var-root (var ~task) (fn [~bind] ~expr)))))

(defmacro disable-task!
  "Disables the given tasks by replacing them with the identity task.

  Example:

  (disable-task! repl jar)"
  [& tasks]
  `(do ~@(for [task tasks]
           `(replace-task! [t# ~task] (fn [& _#] identity)))))

(defmacro task-options!
  "Given a number of task/map-of-curried-arguments pairs, replaces the root
  bindings of the tasks with their curried values.

  Example:

  (task-options!
    repl {:port     12345}
    jar  {:manifest {:howdy \"world\"}})
  
  You can update options, too:

  (task-options!
    jar {:manifest #(assoc % :i-like \"turtles\")})"
  [& task-option-pairs]
  `(do ~@(for [[task opts] (partition 2 task-option-pairs)]
           `(let [opt# ~opts
                  var# (var ~task)
                  old# (:task-options (meta var#))
                  new# (if (map? opt#) opt# (opt# old#))
                  arg# (mapcat identity new#)]
              (replace-task! [t# ~task] (fn [& xs#] (apply t# (concat arg# xs#))))
              (alter-meta! var# (fn [x#] (update-in x# [:task-options] merge new#)))))
       nil))

;; Task Utility Functions ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn json-generate
  "Same as cheshire.core/generate-string."
  [x & [opt-map]]
  (pod/with-call-worker
    (cheshire.core/generate-string ~x ~opt-map)))

(defn json-parse
  "Same as cheshire.core/parse-string."
  [x & [key-fn]]
  (pod/with-call-worker
    (cheshire.core/parse-string ~x ~key-fn)))

(defn yaml-generate
  "Same as clj-yaml.core/generate-string."
  [x]
  (pod/with-call-worker
    (clj-yaml.core/generate-string ~x)))

(defn yaml-parse
  "Same as clj-yaml.core/parse-string."
  [x]
  (pod/with-call-worker
    (clj-yaml.core/parse-string ~x)))

(defn touch
  "Same as the Unix touch(1) program."
  [f]
  (.setLastModified f (System/currentTimeMillis)))

(defn git-files
  "Returns a list of files roughly equivalent to what you'd get with the git
  command line `git ls-files`. The :untracked option includes untracked files."
  [& {:keys [untracked]}]
  (git/ls-files :untracked untracked))

(defn file-filter
  "A file filtering function factory. FIXME: more documenting here."
  [mkpred]
  (fn [criteria files & [negate?]]
    (let [tmp?   (partial satisfies? tmpd/ITmpFile)
          ->file #(if (tmp? %) (tmpd/file %) (io/file %))]
      ((if negate? remove filter)
       #(some identity ((apply juxt (map mkpred criteria)) (->file %))) files))))

(defn by-name
  "This function takes two arguments: `names` and `files`, where `names` is
  a seq of file name strings like `[\"foo.clj\" \"bar.xml\"]` and `files` is
  a seq of file objects. Returns a seq of the files in `files` which have file
  names listed in `names`."
  [names files & [negate?]]
  ((file-filter #(fn [f] (= (.getName f) %))) names files negate?))

(defn not-by-name
  "This function is the same as `by-name` but negated."
  [names files]
  (by-name names files true))

(defn by-ext
  "This function takes two arguments: `exts` and `files`, where `exts` is a seq
  of file extension strings like `[\".clj\" \".cljs\"]` and `files` is a seq of
  file objects. Returns a seq of the files in `files` which have file extensions
  listed in `exts`."
  [exts files & [negate?]]
  ((file-filter #(fn [f] (.endsWith (.getName f) %))) exts files negate?))

(defn not-by-ext
  "This function is the same as `by-ext` but negated."
  [exts files]
  (by-ext exts files true))

(defn by-re
  "This function takes two arguments: `res` and `files`, where `res` is a seq
  of regex patterns like `[#\"clj$\" #\"cljs$\"]` and `files` is a seq of
  file objects. Returns a seq of the files in `files` whose names match one of
  the regex patterns in `res`."
  [res files & [negate?]]
  ((file-filter #(fn [f] (re-find % (.getName f)))) res files negate?))

(defn not-by-re
  "This function is the same as `by-re` but negated."
  [res files]
  (by-re res files true))
