(ns datomic.codeq.analyzer-test
  (:require [clojure.test :refer [deftest testing is]]
            [datomic.codeq.analyzer :as az]))

(deftest sha-matches-known-sha1
  (testing "sha returns lowercase hex sha1 matching commons-codec shaHex output"
    ;; Known SHA-1 values (lowercase hex), verifiable via `printf '%s' "..." | shasum`
    (is (= "da39a3ee5e6b4b0d3255bfef95601890afd80709" (az/sha "")))
    (is (= "a9993e364706816aba3e25717850c26c9cd0d89d" (az/sha "abc")))
    (is (= "0beec7b5ea3f0fdbc95d0dd47f3c5bc275da8a33" (az/sha "foo")))
    (is (= 40 (count (az/sha "anything"))))))
