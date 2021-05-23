(ns eastwood.lint-test
  (:require
   [clojure.test :refer :all]
   [eastwood.copieddeps.dep11.clojure.java.classpath :as classpath]
   [eastwood.copieddeps.dep9.clojure.tools.namespace.dir :as dir]
   [eastwood.copieddeps.dep9.clojure.tools.namespace.track :as track]
   [eastwood.lint :as sut :refer :all]
   [eastwood.reporting-callbacks :as reporting]
   [eastwood.util :as util])
  (:import
   (java.io File)))

(when-not (System/getProperty "eastwood.internal.plugin-profile-active")
  (let [p "eastwood.internal.running-test-suite"
        v (System/getProperty p)]
    (assert (= "true" v)
            (format "The test suite should be running with the %s system property set, for extra test robustness"
                    p))))

(deftest expand-ns-keywords-test
  (testing ""
    (is (= ["foo" "bar" "baz"] (expand-ns-keywords {:source-paths ["foo"]} [:source-paths "bar" "baz"])))))

(deftest last-options-map-adjustments-test
  (let [reporter (reporting/silent-reporter default-opts)]
    (testing "default-options are added"
      (is (= default-opts
             (dissoc (last-options-map-adjustments nil reporter)
                     :warning-enable-config))))
    (testing "passed options are respected. So are the type and sort order of :namespaces."
      (is (= (assoc default-opts
                    :namespaces ["foo" "bar"])
             (dissoc (last-options-map-adjustments {:namespaces ["foo" "bar"]} reporter)
                     :warning-enable-config)))
      (is (= (assoc default-opts
                    :namespaces #{"foo"})
             (dissoc (last-options-map-adjustments {:namespaces #{"foo"}} reporter)
                     :warning-enable-config)))
      (is (= (assoc default-opts
                    :namespaces '("foo" "bar"))
             (dissoc (last-options-map-adjustments {:namespaces '("foo" "bar")} reporter)
                     :warning-enable-config))))
    (testing "all the things are sets (except :namespaces, which keep their original class)"
      (is (empty? (->>
                   (select-keys (last-options-map-adjustments {:namespaces []} reporter) [:debug :source-paths :test-paths :exclude-namespaces])
                   (vals)
                   (remove set?)))))))

(deftest setup-lint-paths-test
  (testing "non-empty source/test paths is respected"
    (is (= {:source-paths #{"lol"}
            :test-paths #{}}
           (setup-lint-paths #{"lol"} nil)))
    (is (= {:source-paths #{}
            :test-paths #{"lol"}}
           (setup-lint-paths nil #{"lol"})))
    (is (= {:source-paths #{"lol"}
            :test-paths #{"bar"}}
           (setup-lint-paths #{"lol"} #{"bar"}))))
  (testing "empty source/test paths yields classpath-directories"
    (with-redefs [classpath/classpath-directories (fn [] [(File. "lol")])]
      (is (= {:source-paths #{(File. "lol")}
              :test-paths #{}}
             (setup-lint-paths nil nil))))))

(def eastwood-src-namespaces (::track/load (dir/scan-dirs (track/tracker) #{"src"})))
(def eastwood-test-namespaces (::track/load (dir/scan-dirs (track/tracker) #{"test"})))
(def eastwood-all-namespaces (concat eastwood-src-namespaces eastwood-test-namespaces))

(deftest nss-in-dirs-test
  (let [dirs #{"src"}]
    (testing "basic functionality"
      (is (= {:dirs (set (map util/canonical-filename dirs))
              :namespaces eastwood-src-namespaces}
             (select-keys (nss-in-dirs dirs 0) [:dirs :namespaces]))))))

(deftest effective-namespaces-test
  (let [source-paths #{"src"}
        test-paths #{"test"}]
    (testing ""
      (is (= {:dirs (concat (map util/canonical-filename source-paths)
                            (map util/canonical-filename test-paths))
              :namespaces eastwood-all-namespaces}
             (select-keys (effective-namespaces #{}
                                                #{:source-paths :test-paths}
                                                {:source-paths source-paths
                                                 :test-paths test-paths} 0)
                          [:dirs :namespaces]))))))

(deftest exceptions-test
  (let [valid-namespaces   (take 1 eastwood-src-namespaces)
        invalid-namespaces '[invalid.syntax]]

    (testing "Reader-level exceptions are reported as such"
      (is (= {:some-warnings true :some-errors true}
             (eastwood.lint/eastwood (assoc eastwood.lint/default-opts :namespaces invalid-namespaces)))))

    (testing "Reader-level exceptions are reported as such"
      (is (-> (with-out-str
                (eastwood.lint/eastwood (assoc eastwood.lint/default-opts :namespaces invalid-namespaces)))
              (.contains "Warnings: 0 (not including reflection warnings)  Exceptions thrown: 1"))))

    (testing "`:rethrow-exceptions?` option"
      (are [namespaces rethrow-exceptions? ok?] (testing [namespaces rethrow-exceptions?]
                                                  (is (try
                                                        (eastwood.lint/eastwood (assoc eastwood.lint/default-opts :namespaces namespaces :rethrow-exceptions? rethrow-exceptions?))
                                                        ok?
                                                        (catch Exception _
                                                          (not ok?))))
                                                  true)
        #_namespaces       #_rethrow-exceptions? #_ok?
        []                 false                 true
        []                 true                  true
        invalid-namespaces false                 true
        invalid-namespaces true                  false
        valid-namespaces   true                  true
        valid-namespaces   false                 true))))

(deftest large-defprotocol-test
  (testing "A large defprotocol doesn't cause a 'Method code too large' exception"
    (is (= {:some-warnings false
            :some-errors false}
           (eastwood.lint/eastwood (assoc eastwood.lint/default-opts :namespaces #{'testcases.large-defprotocol}))))))

(deftest ignore-fault?-test
  (are [input expected] (testing input
                          (is (= expected
                                 (sut/ignore-fault? input
                                                    {:warn-data {:namespace-sym 'some-ns
                                                                 :line 1
                                                                 :column 2
                                                                 :linter :some-linter}})))
                          true)
    nil                                                                        false
    {}                                                                         false
    {:some-linter {'some-ns true}}                                             true
    {:some-linter {'some-ns [true]}}                                           true
    {:different-linter {'some-ns true}}                                        false
    {:different-linter {'some-ns [true]}}                                      false
    {:some-linter {'different-ns true}}                                        false
    {:some-linter {'different-ns [true]}}                                      false
    {:some-linter {'some-ns {:line 1 :column 2}}}                              true
    {:some-linter {'some-ns [{:line 1 :column 2}]}}                            true
    {:some-linter {'some-ns {:line 999 :column 2}}}                            false
    {:some-linter {'some-ns [{:line 999 :column 2}]}}                          false
    {:some-linter {'some-ns {:line 1 :column 999}}}                            false
    {:some-linter {'some-ns [{:line 1 :column 999}]}}                          false
    {:some-linter {'some-ns [{:line 1 :column 2} {:line 1 :column 999}]}}      true
    {:some-linter {'some-ns [{:line 1 :column 999} {:line 1 :column 2}]}}      true
    {:some-linter {'different-ns {:line 1 :column 2}}}                         false
    {:some-linter {'different-ns [{:line 1 :column 2}]}}                       false
    {:some-linter {'different-ns {:line 999 :column 2}}}                       false
    {:some-linter {'different-ns [{:line 999 :column 2}]}}                     false
    {:some-linter {'different-ns {:line 1 :column 999}}}                       false
    {:some-linter {'different-ns [{:line 1 :column 999}]}}                     false
    {:some-linter {'different-ns [{:line 1 :column 2} {:line 1 :column 999}]}} false
    ;; Exercises line-only matching:
    {:some-linter {'some-ns {:line 1}}}                                        true
    {:some-linter {'some-ns [{:line 1}]}}                                      true
    {:some-linter {'some-ns {:line 999}}}                                      false
    {:some-linter {'some-ns [{:line 999}]}}                                    false
    {:some-linter {'some-ns [{:line 1} {:line 1}]}}                            true
    {:some-linter {'some-ns [{:line 1} {:line 2}]}}                            true
    {:some-linter {'some-ns [{:line 2} {:line 1} {:line 2}]}}                  true
    {:some-linter {'different-ns {:line 1}}}                                   false
    {:some-linter {'different-ns [{:line 1}]}}                                 false
    {:some-linter {'different-ns {:line 999}}}                                 false
    {:some-linter {'different-ns [{:line 999}]}}                               false
    {:some-linter {'different-ns [{:line 1} {:line 1}]}}                       false))

(deftest ignored-faults-test
  (testing "A ignored-faults can remove warnings.
The ignored-faults must match ns (exactly) and file/column (exactly, but only if provided)"
    (are [input expected] (testing input
                            (is (= (assoc expected :some-errors false)
                                   (-> eastwood.lint/default-opts
                                       (assoc :namespaces #{'testcases.ignored-faults-example}
                                              :ignored-faults input)
                                       (eastwood.lint/eastwood))))
                            true)
      {}                                                                                  {:some-warnings true}
      {:implicit-dependencies {'testcases.ignored-faults-example true}}                   {:some-warnings false}
      {:implicit-dependencies {'testcases.ignored-faults-example [{:line 4 :column 1}]}}  {:some-warnings false}
      {:implicit-dependencies {'testcases.ignored-faults-example [{:line 4 :column 99}]}} {:some-warnings true}
      {:implicit-dependencies {'testcases.ignored-faults-example [{:line 99 :column 1}]}} {:some-warnings true})))

(deftest const-handling
  (testing "Processing a namespace where `^:const` is used results in no exceptions being thrown"
    (is (= {:some-warnings false
            :some-errors false}
           (eastwood.lint/eastwood (assoc eastwood.lint/default-opts :namespaces '#{testcases.const
                                                                                    testcases.const.unused-namespaces.consumer}))))))

(deftest test-metadata-handling
  (testing "Processing a vanilla defn where `^:test` is used results in no linter faults"
    (is (= {:some-warnings false
            :some-errors false}
           (eastwood.lint/eastwood (assoc eastwood.lint/default-opts :namespaces #{'testcases.test-metadata-example}))))))

(deftest wrong-tag-disabling-test
  (testing "The `:wrong-tag` linter can be selectively disabled via the `disable-warning` mechanism,
relative to a specific macroexpansion"
    (are [input expected] (testing input
                            (is (= (assoc expected :some-errors false)
                                   (-> eastwood.lint/default-opts
                                       (assoc :namespaces #{'testcases.wrong-tag-example})
                                       (merge input)
                                       eastwood.lint/eastwood)))
                            true)
      {}                                                          {:some-warnings true}
      {:builtin-config-files ["disable_wrong_tag.clj"]}           {:some-warnings false}
      {:builtin-config-files ["disable_wrong_tag_unrelated.clj"]} {:some-warnings true})))

(deftest unused-meta-on-macro-disabling-test
  (testing "The `:unused-meta-on-macro` linter can be selectively disabled via the `disable-warning` mechanism,
relative to a specific macroexpansion"
    (are [input expected] (testing input
                            (is (= (assoc expected :some-errors false)
                                   (-> eastwood.lint/default-opts
                                       (assoc :namespaces #{'testcases.wrong-meta-on-macro-example})
                                       (merge input)
                                       eastwood.lint/eastwood)))
                            true)
      {}                                                                     {:some-warnings true}
      {:builtin-config-files ["disable_unused_meta_on_macro.clj"]}           {:some-warnings false}
      {:builtin-config-files ["disable_unused_meta_on_macro_unrelated.clj"]} {:some-warnings true})))

(deftest are-true-test
  (are [desc input expected] (testing input
                               (is (= (assoc expected :some-errors false)
                                      (-> eastwood.lint/default-opts
                                          (assoc :namespaces input)
                                          (eastwood.lint/eastwood)))
                                   desc)
                               true)
    "Supports the \"true at tail position\" pattern for `are`"
    #{'testcases.are-true.green}     {:some-warnings false}

    "does not ruin `(is true)` detection"
    #{'testcases.are-true.red-one}   {:some-warnings true}

    "does not ruin an edge case"
    #{'testcases.are-true.red-two}   {:some-warnings true}

    "only `true` is deemed an apt :qualifier value"
    #{'testcases.are-true.red-three} {:some-warnings true}))

(deftest clojure-test-test
  (testing "Some reported false positives against clojure.test"
    (are [input expected] (testing input
                            (is (= (assoc expected :some-errors false)
                                   (-> eastwood.lint/default-opts
                                       (assoc :namespaces input)
                                       (eastwood.lint/eastwood))))
                            true)
      #{'testcases.clojure-test}         {:some-warnings false}
      #{'testcases.clojure-test.are.red} {:some-warnings true})))

(deftest let-test
  (testing "Some reported false positives against `let`"
    (are [input expected] (testing input
                            (is (= (assoc expected :some-errors false)
                                   (-> eastwood.lint/default-opts
                                       (assoc :namespaces input)
                                       (eastwood.lint/eastwood))))
                            true)
      #{'testcases.let.green} {:some-warnings false}
      #{'testcases.let.red}   {:some-warnings true})))

(deftest while-true-test
  (testing "The `(while true)` idiom is supported"
    (are [input expected] (testing input
                            (is (= (assoc expected :some-errors false)
                                   (-> eastwood.lint/default-opts
                                       (assoc :namespaces input)
                                       (eastwood.lint/eastwood))))
                            true)
      #{'testcases.while-true.green} {:some-warnings false}
      #{'testcases.while-true.red}   {:some-warnings true})))

(deftest is-false-test
  (testing "The `(is false)` idiom is supported"
    (are [input expected] (testing input
                            (is (= (assoc expected :some-errors false)
                                   (-> eastwood.lint/default-opts
                                       (assoc :namespaces input)
                                       (eastwood.lint/eastwood))))
                            true)
      #{'testcases.is-false.green} {:some-warnings false}
      ;; no 'red' case for now, since there isn't one that would make sense today (given git.io/J35fr)
      )))

(deftest cond-test
  (testing "Some reported false positives against `cond`"
    (are [input expected] (testing input
                            (is (= (assoc expected :some-errors false)
                                   (-> eastwood.lint/default-opts
                                       (assoc :namespaces input)
                                       (eastwood.lint/eastwood))))
                            true)
      #{'testcases.cond.green} {:some-warnings false}
      ;; some 'red' cases could be added at some point, there are no reported issues atm though.
      )))

(deftest bytes-class-test
  (testing "https://github.com/jonase/eastwood/issues/385"
    (is (= {:some-warnings false :some-errors false}
           (eastwood.lint/eastwood (assoc eastwood.lint/default-opts :namespaces #{'testcases.bytes-class.green}))))))
