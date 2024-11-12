(ns com.phronemophobic.whisper.impl.raw
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.edn :as edn]
            [com.phronemophobic.clong.gen.jna :as gen])
  (:import
   java.io.PushbackReader
   com.sun.jna.Memory
   com.sun.jna.Pointer
   com.sun.jna.ptr.PointerByReference
   com.sun.jna.ptr.LongByReference
   com.sun.jna.Structure)
  (:gen-class))

(defn ^:private write-edn [w obj]
  (binding [*print-length* nil
            *print-level* nil
            *print-dup* false
            *print-meta* false
            *print-readably* true

            ;; namespaced maps not part of edn spec
            *print-namespace-maps* false

            *out* w]
    (pr obj)))

(defn dump-api []
  (let [outf (io/file
              "resources"
              "com"
              "phronemophobic"
              "whisper"
              "api.edn")]
    (.mkdirs (.getParentFile outf))
    (with-open [w (io/writer outf)]
      (write-edn w
                 ((requiring-resolve 'com.phronemophobic.clong.clang/easy-api)
                  (.getCanonicalPath (io/file "../"
                                              "whisper.cpp"
                                              "include"
                                              "whisper.h"))
                  (into @(requiring-resolve 'com.phronemophobic.clong.clang/default-arguments)
                        [(str
                          "-I"
                          (.getCanonicalPath (io/file "../"
                                                      "whisper.cpp"
                                                      "ggml"
                                                      "include")))]))))))

(defn load-api []
  (with-open [rdr (io/reader
                   (io/resource
                    "com/phronemophobic/whisper/api.edn"))
              rdr (java.io.PushbackReader. rdr)]
    (edn/read rdr)))


(def lib
  (delay
    ;; whisper depends on ggml.
    (com.sun.jna.NativeLibrary/getInstance "ggml")
    (com.sun.jna.NativeLibrary/getInstance "whisper")))

(def raw-api
  (load-api))

(defn ^:private whisper-prefix? [o]
  (or (str/starts-with? (name (:id o))
                        "whisper")
      ;; for Anonymous structs
      (str/starts-with? (name (:id o))
                        "Struct")
      ))

(def api
  (-> raw-api
      (update :structs #(filterv whisper-prefix? %))
      (update :functions #(filterv whisper-prefix? %))))

(gen/def-api-lazy lib api)

(comment
  (->> api
       :structs
       (map :id)
       sort
       clojure.pprint/pprint)

  ,)
