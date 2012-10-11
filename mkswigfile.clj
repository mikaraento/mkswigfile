;; Copyright 2012 Mika Raento (mikie@iki.fi)
;;
;; Licensed under The BSD 2-Clause License

(ns mkswigfile
  "Loading of new swig code into your jvm - like mkoctfile for octave.

   The swig code must:
      - only declare functions, not classes
      - use not-publicly-visible implementations (either
        use static or an anonumous namespace).
      - not contain a %module statement

   You should be able to use whatever typemaps you think
   make sense for your code."
  (:use clojure.java.io
        clojure.java.shell))

(defn- make-def
  "Define a function in this namespace that has the same name
   as the given (static) Method and calls the method.

   We have to use intern and not defn (even through a macro)
   as all macros are expanded at compile time."
  [^java.lang.reflect.Method method]
  (let [nm-sym (symbol (.getName method))]
    (ns-unmap *ns* nm-sym)
    (intern *ns* nm-sym (fn [& args]
                          (.invoke method nil (object-array args))))))

(defn- sh-or-die
  "Call clojure.java.shell/sh with the given args and check that
   the command succeeded. If it didn't throw an Error."
  [& args]
  (let [ret (apply sh args)]
    (when-not (zero? (get ret :exit))
      (throw (Error. ^String
                    (apply str (concat ["Failed to execute command "]
                                        (interleave args (repeat " "))
                                        [" returned " (str ret)])))))))

(defn mkswigfile
  "Compile the given swig file that should only have function declarations
   (no classes), load the new code (class) and define the functions in
   the swig file in this namespace.

   The default flags only work on OS-X jni.h in the place where my Java
   installation put it, and require code to pass as c++."
  [^String filename0
   &{:keys [swig cc swigflags ccflags]
     :or {swig "swig"
          cc "g++"
          ccflags ["-fPIC" "-shared"
                   "-flat_namespace"
                   "-I/System/Library/Frameworks/JavaVM.framework/Headers/"]
          swigflags ["-c++"]}}]
  (let [^String contents (slurp (as-file filename0))
        filename (.replaceAll filename0 "/" "_")
        ;; Use java tmpdir for the created swig, java and c++ source
        ;; files as well as compiled class and so files.
        tmpdir (as-file (System/getProperty "java.io.tmpdir"))
        id (str "mksf" (.replaceAll (.toString (java.util.UUID/randomUUID))
                                    "-" ""))
        out-dir (file tmpdir (as-file id))
        wrapped (.replace filename ".i" "_wrap.cxx")
        i-file (file out-dir (as-file filename))
        jnilib-file (file out-dir (as-file "libmksf.so"))
        jnilib (.getAbsolutePath jnilib-file)]
    (assert (not (.exists out-dir)))
    (assert (.mkdir out-dir))
    (with-open [i-writer (writer i-file)]
      ;; The module becomes the java class name, have to have one
      (.write i-writer (str "%module mkswigfile\n"))
      (.write i-writer contents)
      ;; The library needs to be loaded from the jni wrapper class
      ;; so that's in the right classloader.
      (.write i-writer "%pragma(java) jniclasscode=%{\n")
      (.write i-writer (str "  static { System.load(\"" jnilib "\"); }"))
      (.write i-writer "%}\n"))
    (with-sh-dir out-dir
      (apply sh-or-die (concat [swig]
                               swigflags
                               ["-package" id "-java" filename]))
      (sh-or-die "bash" "-c" "javac *java")
      (apply sh-or-die
             (concat [cc]
                     ccflags
                     ["-o" jnilib wrapped])))
    ;; Have to use a separate classloader to access the tmpdir.
    ;; If things go really well once you reload a new version the
    ;; classloader becomes available for GC and eventually even
    ;; unloads the native library.
    (let [url (java.net.URL. (str "file:" (.getAbsolutePath tmpdir) "/"))
          urls (into-array java.net.URL [url])
          loader (java.net.URLClassLoader. urls)
          c (.loadClass loader (str id ".mkswigfile"))
          ms (.getMethods c)
          own-ms (filter #(= (.getDeclaringClass ^java.lang.reflect.Method %)
                             c)
                         ms)]
      (doall (map make-def own-ms)))))
