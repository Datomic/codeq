;;   Copyright (c) Metadata Partners, LLC. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns datomic.codeq.core
  (:require [datomic.api :as d]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import java.util.Date)
  (:gen-class))

(set! *warn-on-reflection* true)

(defn ^java.io.Reader exec-stream 
  [^String cmd]
  (-> (Runtime/getRuntime)
      (.exec cmd) 
      .getInputStream 
      io/reader))

(defn install-schema [conn]
  )

;;example commit
;;tree d81cd432f2050c84a3d742caa35ccb8298d51e9d
;;author Rich Hickey <richhickey@gmail.com> 1348842448 -0400
;;committer Rich Hickey <richhickey@gmail.com> 1348842448 -0400

;;example tree
;;100644 blob ee508f768d92ba23e66c4badedd46aa216963ee1	.gitignore
;;100644 blob b60ea231eb47eb98395237df17550dee9b38fb72	README.md
;;040000 tree bcfca612efa4ff65b3eb07f6889ebf73afb0e288	doc
;;100644 blob 813c07d8cd27226ddd146ddd1d27fdbde10071eb	epl-v10.html
;;100644 blob f8b5a769bcc74ee35b9a8becbbe49d4904ab8abe	project.clj
;;040000 tree 6b880666740300ac57361d5aee1a90488ba1305c	src
;;040000 tree 407924e4812c72c880b011b5a1e0b9cb4eb68cfa	test

(defn sources 
  [src? path tree]
  (with-open [s (exec-stream (str "git cat-file -p " tree))]
    (let [es
          (map
           #(vec (string/split ^String % #"\s"))
           (line-seq s))]
      (reduce (fn [ret [_ type sha file]]
                (cond
                 (and (= type "blob") (src? file))
                 (conj ret {:file (str path file) :sha sha})

                 (= type "tree")
                 (into ret (sources src? (str path file "/") sha))

                 :else ret))
              [] es))))

(defn commit
  [sha doc]
  (let [trim-email (fn [s] (subs s 1 (dec (count s))))
        dt (fn [ds] (Date. (* 1000 (Integer/parseInt ds))))
        [tree author committer]
        (with-open [s (exec-stream (str "git cat-file -p " sha))]
          (mapv
           #(vec (reverse (.split ^String % " ")))
           (take 3 (line-seq s))))]
    {:sha sha
     :doc doc
     :sources (sources #(.endsWith ^String % ".clj") "" (tree 0))
     :author (trim-email (author 2))
     :authored (dt (author 1))
     :committer (trim-email (committer 2))
     :committed (dt (committer 1))}))

(defn commits []
  (let [commits (with-open [s (exec-stream "git log --pretty=oneline")]
                  (reverse
                   (mapv
                    #(commit (subs % 0 40)
                             (subs % 41 (count %)))
                    (line-seq s))))] 
    commits))

(defn ensure-db [db-uri]
  (let [newdb? (d/create-database db-uri)
        conn (d/connect db-uri)]
    (when newdb?
      (install-schema conn))
    conn))

(defn -main
  [& [db-uri]]
  (if db-uri
    (do 
      (ensure-db db-uri) 
      (commits))
    (println "Usage: datomic.codeq.core db-uri")))


(comment
(datomic.codeq.core/-main "datomic:mem://test")
)