(ns puppetlabs.puppetdb.http.packages-test
  (:require
   [clojure.test :refer :all]
   [flatland.ordered.map :as omap]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [puppetlabs.puppetdb.testutils.db :refer [*db*]]
   [puppetlabs.puppetdb.testutils.http
    :refer [deftest-http-app is-query-result]]
   [puppetlabs.puppetdb.time :refer [days ago]]
   [puppetlabs.puppetdb.scf.storage :as scf-store]))

(defn package-map [certname package-name version provider]
  {:certname certname
   :package_name package-name
   :version version
   :provider provider})

(def queries->results
  (omap/ordered-map
   ["=" "certname" "node1"]
   #{(package-map "node1" "foo" "1.2.3" "apt")
     (package-map "node1" "bar" "2.3.4" "apt")
     (package-map "node1" "baz" "3.4.5" "apt")}


   "package_inventory { certname = 'node1' }"
   #{(package-map "node1" "foo" "1.2.3" "apt")
     (package-map "node1" "bar" "2.3.4" "apt")
     (package-map "node1" "baz" "3.4.5" "apt")}


   ["and" ["=" "package_name" "bar"] ["=" "version" "2.3.4"]]
   #{(package-map "node1" "bar" "2.3.4" "apt")
     (package-map "node2" "bar" "2.3.4" "apt")}


   ["extract" ["certname" "package_name" "version" "provider"]
    ["and"
     ["~" "package_name" "ba?"]]]
   #{(package-map "node1" "bar" "2.3.4" "apt")
     (package-map "node1" "baz" "3.4.5" "apt")
     (package-map "node2" "bar" "2.3.4" "apt")}

   ["extract" ["package_name" "version" ["function" "count" "certname"]]
    ["~" "package_name" "ba?"]
    ["group_by" "package_name" "version"]]
   #{{:package_name "bar" :version "2.3.4" :count 2}
     {:package_name "baz" :version "3.4.5" :count 1}}

   ;; limited package count aggregate
   ["extract" ["package_name" "version" ["function" "count"]]
    ["in" "package_name" ["from" "packages"
                          ["extract" ["package_name"]]
                          ["order_by" ["package_name"]]
                          ["limit" 100]]]
    ["group_by" "package_name" "version" "provider"]]
   #{{:package_name "foo" :version "1.2.3" :count 2}
     {:package_name "bar" :version "2.3.4" :count 2}
     {:package_name "baz" :version "3.4.5" :count 1}}

   ["extract" ["package_name" "version" ["function" "count"]]
    ["in" "package_name" ["from" "packages"
                          ["extract" ["package_name"]
                           ["~" "package_name" "ba?"]]
                          ["order_by" ["package_name"]]
                          ["limit" 100]]]
    ["group_by" "package_name" "version" "provider"]]
   #{{:package_name "bar" :version "2.3.4" :count 2}
     {:package_name "baz" :version "3.4.5" :count 1}}

   ;; subquery to facts
   ["extract" ["certname" "version"]
    ["and"
     ["=" "package_name" "foo"]
     ["subquery" "fact_contents"
      ["and"
       ["~>" "path" ["os" "name"]]
       ["=" "value" "Ubuntu"]]]]]
   #{{:certname "node1" :version "1.2.3"}}))

(deftest-http-app package-query-test
  [method [:get :post]]
  (let [fact-cmd-1 {:certname "node1"
                    :values {"os" {"name" "Ubuntu", "family" "Debian"}}
                    :timestamp (-> 1 days ago)
                    :environment "DEV"
                    :producer_timestamp (-> 1 days ago)
                    :producer "producer"
                    :package_inventory [["foo" "1.2.3" "apt"]
                                        ["bar" "2.3.4" "apt"]
                                        ["baz" "3.4.5" "apt"]]}
        fact-cmd-2 {:certname "node2"
                    :values {"os" {"name" "Debian", "family" "Debian"}}
                    :timestamp (-> 1 days ago)
                    :environment "DEV"
                    :producer_timestamp (-> 1 days ago)
                    :producer "producer"
                    :package_inventory [["foo" "1.2.3" "apt"]
                                        ["bar" "2.3.4" "apt"]]}]
    (jdbc/with-transacted-connection *db*
      (scf-store/add-certname! "node1")
      (scf-store/add-certname! "node2")
      (scf-store/add-facts! fact-cmd-1)
      (scf-store/add-facts! fact-cmd-2))

    (doseq [[query result] queries->results]
      (if (vector? query)
        (do (is-query-result method "/v4" ["from" "package_inventory" query]
                             result)
            (is-query-result method "/v4/package-inventory" query
                             result))
        (is-query-result method "/v4" query result)))

    (testing "querying for a specific node"
      (is-query-result method "/v4/package-inventory/node2" nil
                       #{(package-map "node2" "bar" "2.3.4" "apt")
                         (package-map "node2" "foo" "1.2.3" "apt")}))

    (testing "top level packages endpoint"
      (is-query-result method "/v4/packages" nil
                       #{{:package_name "foo", :version "1.2.3", :provider "apt"}
                         {:package_name "bar", :version "2.3.4", :provider "apt"}
                         {:package_name "baz", :version "3.4.5", :provider "apt"}}))

    (testing "unique package count"
      (is-query-result method "/v4" ["from" "packages"
                                     ["extract" [["function" "count"]]]]
                       #{{:count 3}})

      (is-query-result method "/v4" ["from" "packages"
                                     ["extract" [["function" "count"]]
                                      ["~" "package_name" "ba?"]]]
                       #{{:count 2}}))))
