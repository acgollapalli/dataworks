(defproject dataworks "0.5.0-epsilon"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "0.7.559"]
                 [clj-http "3.10.0"]
                 [yada "1.3.0-alpha9"]
                 [aleph "0.4.6"]
                 [bidi "2.1.6"]
                 [cheshire "5.9.0"]
                 [mount "0.1.16"]
                 [tick "0.4.23-alpha"]
                 [buddy/buddy-hashers "1.4.0"]
                 [juxt/crux-core "20.04-1.8.2-alpha"]
                 [juxt/crux-kafka "20.04-1.8.2-alpha"]
                 [juxt/crux-rocksdb "20.04-1.8.2-alpha"]]
  :main ^:skip-aot dataworks.core
  :target-path "target/%s"
  :plugins  [[lein-cljfmt "0.6.7"]]
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[com.clojure-goes-fast/clj-memory-meter "0.1.2"]
                                  [juxt/crux-kafka-embedded "20.04-1.8.2-alpha"]]
                   :jvm-opts ["-Djdk.attach.allowAttachSelf"] }})
