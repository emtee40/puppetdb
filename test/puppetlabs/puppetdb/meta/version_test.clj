(ns puppetlabs.puppetdb.meta.version-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.meta.version :as version]
   [puppetlabs.puppetdb.testutils.db :refer [with-test-db *db*]]))

(deftest check-for-updates!-test
  (testing "check-for-updates! swallows exceptions"
    (testing "can't connect to server"
      (with-test-db
        (is (nil? (version/check-for-updates! "http://updates.foo.com" *db*)))))
    (testing "can't connect to database"
      (is (nil? (version/check-for-updates! "http://updates.puppetlabs.com"
                                            {:invalid :connection}))))))
