(ns puppetlabs.puppetdb.pdb-routing-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.time :as time :refer [now to-string]]
            [puppetlabs.puppetdb.testutils.dashboard :as dtu]
            [puppetlabs.puppetdb.pdb-routing
             :refer [disable-maint-mode enable-maint-mode]]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.http.client.sync :as http]))

(defn submit-facts [base-url certname facts]
  (svc-utils/sync-command-post base-url certname "replace facts" 4 facts))

(defn query-fact-names [_]
  (svc-utils/get (svc-utils/query-url-str "/fact-names")))

(defn export [_]
  (svc-utils/get (svc-utils/admin-url-str "/archive")))

(defn query-server-time [_]
  (svc-utils/get (svc-utils/meta-url-str "/server-time")))

(defn construct-metrics-url []
      (-> svc-utils/*base-url*
          (assoc :prefix "/metrics" :version :v2)
          (svc-utils/create-url-str "/list")))

(def test-facts {:certname "foo.com"
                 :environment "DEV"
                 :producer_timestamp (to-string (now))
                 :values {:foo 1
                          :bar "2"
                          :baz 3}})

(deftest top-level-routes
  (svc-utils/with-puppetdb-instance
    (let [pdb-resp (http/get (svc-utils/root-url-str) {:as :text})]
      (tu/assert-success! pdb-resp)
      (is (dtu/dashboard-page? pdb-resp))

      (is (-> (query-server-time svc-utils/*base-url*)
              (get-in [:body :server_time])
              time/parse-wire-datetime))

      (let [resp (export svc-utils/*base-url*)]
        (tu/assert-success! resp)
        (is (.contains (get-in resp [:headers "content-disposition"]) "puppetdb-export"))
        (is (:body resp))))))

(deftest maintenance-mode
  (svc-utils/with-puppetdb-instance
    (let [maint-mode-service (tk-app/get-service svc-utils/*server* :MaintenanceMode)]
      (is (= 200 (:status (submit-facts (svc-utils/pdb-cmd-url) "foo.com" test-facts))))
      (is (= #{"foo" "bar" "baz"}
             (-> (query-fact-names svc-utils/*base-url*)
                 :body
                 set)))
      (enable-maint-mode maint-mode-service)
      (is (= (:status (query-fact-names svc-utils/*base-url*))
             503))
      (testing "query metrics successfully"
      (is (= 200 (:status (svc-utils/get (construct-metrics-url))))))

      (disable-maint-mode maint-mode-service)
      (is (= #{"foo" "bar" "baz"}
             (-> (query-fact-names svc-utils/*base-url*)
                 :body
                 set))))))
