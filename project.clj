(defproject datomic/codeq "0.1.0-SNAPSHOT"
  :description "codeq does a code-aware import of your git repo into a Datomic db"
  :url "http://datomic.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main datomic.codeq.core
  :plugins [[lein-tar "1.1.0"]]
  :repositories [["jgit-repository" "https://repo.eclipse.org/content/groups/releases/"]]
  :dependencies [[com.datomic/datomic-free "0.8.4020.24"]
                 [commons-codec "1.7"]
                 [org.clojure/clojure "1.5.1"]
                 [org.eclipse.jgit/org.eclipse.jgit "3.0.3.201309161630-r"]]
  :source-paths ["src" "examples/src"])
