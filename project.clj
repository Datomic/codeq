(defproject datomic/codeq "0.1.0-SNAPSHOT"
  :description "codeq does a code-aware import of your git repo into a Datomic db"
  :url "http://datomic.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main datomic.codeq.core
  :dependencies [[com.datomic/datomic-free "0.8.3538"]
                 [commons-codec "1.7"]
                 [local.repo/clojure "1.5.0-alpha6"]
                 #_[org.clojure/clojure "1.5.0-alpha6"]])
