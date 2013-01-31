(ns datomic.codeq.examples.clojure-and-contrib
  (:require [datomic.api :as d :refer [q]]
            [clojure.pprint :refer [pprint]]))

(defn -main [& [database-uri]]
  (assert database-uri)
  (println "Running clojure-and-contrib examples with database" database-uri)
  (try
    (let [conn        (d/connect database-uri)
          db          (-> conn d/db (d/as-of 435691))
          repos       (map first
                           (q '[:find ?repo
                                :where [?e :repo/uri ?repo]]
                              db))
          namespaces  (map first
                           (q '[:find ?ns
                                :where
                                [?e :clj/ns ?n]
                                [?n :code/name ?ns]]
                              db))
          definitions (reduce (fn [agg [o d]]
                                (update-in agg [o] (fnil conj []) d))
                              {}
                              (q '[:find ?op ?def
                                   :where
                                   [?e :clj/def ?d]
                                   [?e :clj/defop ?op]
                                   [?d :code/name ?def]]
                                 db))]
      (println)
      (println "#### Repos:")
      (println)
      (pprint repos)
      (println)
      (println "#### Namespaces:")
      (println)
      (pprint namespaces)
      (println)
      (println "#### Definitions:")
      (println)
      (pprint definitions)
      (assert (= 44 (-> definitions keys count)))
      (assert (= 33 (-> "defne" definitions count))))
    (finally
      ;; (shutdown-agents)
      (System/exit 0))))
