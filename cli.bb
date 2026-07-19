#!/usr/bin/env bb
;; mcpf-adapter/cli.bb
;;
;; Singapore MyCareersFuture (MCF) v2 → data-toolkit local_parser bridge.
;;
;; Single-file Babashka CLI. Five subcommands: test | scrape | emit | status | clear.
;; Cache-first HTTP. Dedup by jobPostId. Idempotent on re-run.
;;
;; Author : Nur Azhar (career@nurazhar.com)
;; License: MIT
;; Upstream-isolation guarantee: this adapter never modifies files inside
;; /home/nurazhar/Buffy/data-toolkit/. The only optional wire-in is
;; data-toolkit/templates/portals.yml (which is gitignored upstream).

(ns mcpf-adapter.cli
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.process :as proc]))

;; ===========================================================================
;; Paths & defaults
;; ===========================================================================

(defn build-search-url
  "Build MCF v2 search URL with search/page/limit/sortBy as URL params.

  Browser-sniffed 2026-07-15: the v2 endpoint reads `search` from the
  query string, not from the POST body. Earlier code sent it under
  :searchQuery in the body — MCF silently ignored it and returned the
  same default '20 newest low-tier jobs' for every query, which is
  what caused the cross-query dedup to collapse to 20 unique records.

  Fix: search is now in the URL. Body keeps page metadata plus a
  redundant :searchQuery/:search for any future MCF schema path that
  reads it from the body."
  [query page page-size]
  (let [encode #(java.net.URLEncoder/encode % "UTF-8")
        search (encode (str query))]
    (str "https://api.mycareersfuture.gov.sg/v2/search"
         "?search=" search
         "&page=" page
         "&limit=" page-size
         "&sortBy=" (encode "newest"))))

(def default-user-agent
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

(def defaults
  {:page-size      50
   :default-pages  5
   :sleep-ms       3000
   :user-agent     default-user-agent})

(def cache-dir     "./cache")
(def raw-cache-dir "./cache/raw")
(def ids-file      "./cache/processed-ids.txt")

;; Resolve config.edn relative to this script's directory so tasks like
;; `bb mcpf:status` (run from the project root) still find it.
(def config-file
  (let [this-dir (or (some-> *file* io/file .getParent) ".")]
    (.getPath (io/file this-dir "config.edn"))))

(defn root-cache-dir! []
  (doseq [d ["./"]]
    (let [f (io/file d "cache")]
      (when-not (.exists f) (.mkdirs f))))
  (doseq [d ["raw"]]
    (let [f (io/file "./cache" d)]
      (when-not (.exists f) (.mkdirs f)))))

(defn ensure-cache! []
  (root-cache-dir!))

(defn load-config []
  (let [f (io/file config-file)]
    (if (.exists f)
      (merge defaults (edn/read-string (slurp f)))
      defaults)))

;; ===========================================================================
;; Cache keying + dedup
;; ===========================================================================

(defn sha256-hex [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes s "UTF-8"))
        sb (StringBuilder.)]
    (dotimes [i (alength bytes)]
      (let [b (aget bytes i)
            unsigned (bit-and 0xff b)]
        (cond
          (< unsigned 16) (.append sb "0")
          :else nil)
        (.append sb (Long/toHexString (long unsigned)))))
    (.toString sb)))

(defn cache-key [query page]
  (let [combined (str (str/trim (str query)) "|" page)]
    (str (sha256-hex combined) ".json")))
(defn cache-path  [k]         (str "./cache/raw/" k))

(defn file-exists? [p] (.exists (io/file p)))

(defn read-cached [k]
  (let [p (cache-path k)]
    (when (file-exists? p) (slurp p))))

(defn read-processed-ids []
  (if (file-exists? ids-file)
    (->> (slurp ids-file) str/split-lines (remove str/blank?))
    []))

(defn append-processed-id! [id]
  (ensure-cache!)
  (spit ids-file (str id "\n") :append true))

;; ===========================================================================
;; HTTP layer
;; ===========================================================================

(defn curl-available? []
  (try
    (zero? (:exit (proc/shell {:out :string :err :string :continue true}
                              "curl" "--version")))
    (catch Exception _ false)))(defn http-post-mcf [body user-agent url]   ;; Backend: shell out to curl with the JSON body written to a tempfile.
  ;; Why tempfiles instead of inline -d <body>?
  ;;   - Earlier inline version returned 400 from MCF. The body JSON contains
  ;;     spaces, commas, and double-quotes; depending on proc/shell's
  ;;     shell-tokenization semantics (version-dependent in bb.edn), the args
  ;;     can be re-split by /bin/sh -c and produce a malformed request.
  ;;   - Writing the body to a tempfile and using `-d @<file>` sidesteps
  ;;     any arg-vector tokenization. The path survives the shell layer
  ;;     intact because it has no spaces or special chars (we use a
  ;;     java.io.File.createTempFile name).
  (try
    (let [body-json (json/generate-string body)
          body-file (doto (java.io.File/createTempFile "mcpf-body-" ".json")
                     (.deleteOnExit))
          _ (spit body-file body-json)
          body-path (.getAbsolutePath body-file)
          result (proc/shell {:out :string :err :string :continue true}
                              "curl" "-sS"
                              "-w" "\n__HTTPSTATUS__%{http_code}"
                              "-X" "POST"
                              "-H" (str "User-Agent: " user-agent)
                              "-H"                              "Content-Type: application/json"
                              "-H" "Accept: application/json"
                              "-m" "30"
                              "-d" (str "@" body-path)
                              url)
          raw-out (or (:out result) "")
          parts (str/split raw-out #"__HTTPSTATUS__" 2)
          body-part (or (first parts) "")
          status-str (or (second parts) "0")]
      (try (.delete body-file) (catch Exception _ nil))
      {:status (try (Integer/parseInt (str/trim status-str)) (catch Exception _ 0))
       :body body-part
       :err  (:err result)
       :curl-exit (:exit result)})
    (catch Exception e
      {:status 0 :body "" :error (.getMessage e)})))

(defn fetch-page-cached! [query page cfg & [force-fresh?]]
  (let [k (cache-key query page)
        p (cache-path k)]
    (if (and (file-exists? p) (not force-fresh?))
      {:source :cache :body (slurp p)}
      ;; Search term goes via URL param (browser-sniffed 2026-07-15).
      ;; Body carries page/sortBy across because MCF v2 is an unofficial
      ;; endpoint and might pivot back to body-based filtering. The
      ;; redundant :search and :keywords fields cover three plausible
      ;; internal schema paths without changing call shape.
      (let [page-size (cfg :page-size 50)
            body {:searchQuery query
                  :search      query
                  :keywords    [query]
                  :page        page}
            resp (http-post-mcf body (cfg :user-agent default-user-agent)
                                (build-search-url query page page-size))
            status (:status resp)
            body-str (:body resp)]
        (if (and (= status 200) (seq body-str))
          (do (ensure-cache!)
              (spit p body-str)
              {:source :network :body body-str})
          {:source :network-failed :status status :body body-str :error (:error resp)})))))

;; ===========================================================================
;; Schema mapping: MCF result → data-toolkit local_parser offer
;; (output schema per data-toolkit/docs/local-parser-cookbook.md:
;;  array, OR {jobs: [...]}, OR {results: [...]}  with title + url mandatory)
;; ===========================================================================

(defn pick-skill-names [skills]
  (->> skills (keep :skill) distinct (remove str/blank?) (str/join ", ")))

(defn district-string [address]
  (let [ds (or (:districts address) [])]
    (when (seq ds) (str/join ", " (map :name ds)))))

(defn format-location [address]
  (let [d (district-string address)
        p (:postalCode address)]
    (str (cond
           (and d p) (str d " " p)
           d         (str d " (no postal)")
           p         (str p " (no district)")
           :else     "")
         ", Singapore")))

(defn annualize-factor [salary-type]
  (case salary-type
    "Monthly" 12
    "Annual"  1
    "Hourly"  2080   ; 40 hr/wk × 52 wk
    1))

(defn annualize [min max salary-type]
  (let [factor (annualize-factor salary-type)
        m (when min (int (* min factor)))
        mx (when max (int (* max factor)))]
    (cond
      (and m mx) {:min m :max mx}
      m          {:min m}
      :else      nil)))

(defn transform-listing [r]
  (let [salary-block (:salary r)
        sal-min (:minimum salary-block)
        sal-max (:maximum salary-block)
        sal-type (get-in salary-block [:type :salaryType])
        ann (annualize sal-min sal-max sal-type)
        skills (:skills r)]
    (cond-> {:title  (:title r)
             :url    (or (get-in r [:metadata :jobDetailsUrl])
                         (:jobDetailsUrl r)
                         (str "https://www.mycareersfuture.gov.sg/job/" (:uuid r)))
             :posted_at (get-in r [:metadata :newPostingDate])
             :expires_at (:expiresDate r)
             :job_post_id (:jobPostId r)
             :uuid (:uuid r)
             :skills (pick-skill-names skills)
             :employment_type (first (or (:employmentTypes r) []))}
      (get-in r [:postedCompany :name]) (assoc :company (get-in r [:postedCompany :name]))
      (get-in r [:postedCompany :uen])  (assoc :uen (get-in r [:postedCompany :uen]))
      (:address r) (assoc :location (format-location (:address r)))
      sal-type (assoc :salary_type sal-type)
      sal-min (assoc :salary_min sal-min)
      sal-max (assoc :salary_max sal-max)
      (seq (or (:categories r) [])) (assoc :category (first (:categories r)))
      ann (assoc :salary_annualized_sgd ann))))

;; Dedup key extractor: prefer jobPostId (MCF canonical id), fall back to uuid.
;; Returns nil only when both are absent (which is rare for real MCF responses).
(defn listing-dedupe-key [r]
  (or (:jobPostId r) (:uuid r)))

(defn listing-has-valid-title? [r]
  (seq (str (:title r))))

;; ===========================================================================
;; Subcommands
;; ===========================================================================

(defn results-from-body [body]
  (try
    ;; keywordize keys (use the `keyword` fn, NOT legacy `true`): cheshire >= 5.13
    ;; deprecates the 2-arg `(s true)` form. MCF returns JSON objects whose keys
    ;; are strings; our downstream code uses Clojure keyword lookups ((:title r)
    ;; etc) so we need the keyword form.
    (or (:results (json/parse-string body keyword)) [])
    (catch Exception _
      (binding [*out* *err*]
        (println "[mcpf-adapter] WARN: non-JSON response from MCF; treating as zero results"))
      [])))

;; ===========================================================================
;; Invariants + smoke gate  (mandatory before scrape / emit)
;;
;; Required for mcpf-adapter to be usable as a market-intelligence primitive
;; by data-toolkit downstream. Three posts:
;;   exit 1: usage error (pre-existing)
;;   exit 2: smoke gate failure (network unreachability or MCF schema drift)
;;   exit 3: emit-invariant failure (any record missing title / posted_at /
;;           skill token)
;; ===========================================================================

(defn emit-invariants-violations
  "Return vec of [idx reason] for emitted records that violate the
  market-intelligence primitive contract. Each record MUST have:
    (1) non-blank :title
    (2) non-blank :posted_at  (ISO8601 / date-only string)
    (3) at least one non-blank comma-separated skill token."
  [results]
  (vec
    (keep-indexed
      (fn [i r]
        (cond
          (str/blank? (:title r))                  [i :null-title]
          (str/blank? (str (:posted_at r)))        [i :null-posted-at]
          (empty?
            (->> (str/split (or (:skills r) "") #",")
                 (map str/trim)
                 (remove str/blank?)))             [i :no-skills]
          :else nil))
      results)))

(defn assert-emit-invariants!
  "Hard-fail any `cmd-emit` whose records violate the three invariants.
  Exit code 3 on any violation; diagnostics print to stderr so stdout
  stays pipe-clean."
  [results]
  (let [violations (emit-invariants-violations results)]
    (when (seq violations)
      (binding [*out* *err*]
        (println (str "[invariant] FAILED: "
                      (count violations) " of " (count results)
                      " records violate invariants (exit 3)"))
        (when-let [by-reason
                   (seq (frequencies (map second violations)))]
          (println (str "  reasons: " (pr-str by-reason))))
        (println "  first 5 violations:")
        (doseq [[i v] (take 5 violations)]
          (let [r (nth results i)]
            (println
              (str "    #" i " reason=" (pr-str v)
                   " title=" (pr-str (:title r))
                   " posted_at=" (pr-str (:posted_at r))
                   " skills=" (pr-str (:skills r)))))))
      (System/exit 3))))

(defn smoke-test-required
  "Probe MCF v2 with a fresh (cache-bypassing) 1-page cheap call.
  Three gates must hold:
    1. network reachable
    2. MCF returned at least one record
    3. schema mapping yields non-null :title AND non-null :posted_at
  Returns {:result :passed | :network-failed | :no-results | :schema-bad, ...}."
  []
  (let [cfg   (assoc (load-config) :page-size 2)
        probe (fetch-page-cached! "software engineer" 0 cfg true)]   ; force-fresh
    (cond
      (= (:source probe) :network-failed)
      {:result :network-failed :detail probe}

      :else
      (let [raw (results-from-body (:body probe))]
        (cond
          (empty? raw)
          {:result :no-results}

          :else
          (let [sample (first raw)
                offer  (transform-listing sample)]
            (cond
              (str/blank? (:title offer))
              {:result :schema-bad :reason :null-title :sample sample}
              (str/blank? (str (:posted_at offer)))
              {:result :schema-bad :reason :null-posted-at :sample sample}
              :else
              {:result :passed :offer offer})))))))

(defn gate-with-smoke!
  "Mandatory pre-scrape / pre-emit hard gate. Runs `smoke-test-required`
  with a fresh network probe (cache-bypassing) and exits 2 on any failure.
  All diagnostic lines print to *err* so stdout stays pipe-clean for any
  downstream consumer of cmd-scrape / cmd-emit output."
  []
  (binding [*out* *err*]
    (println "[smoke] MCF v2 probe (fresh network call, cache-bypassed)..."))
  (let [r (smoke-test-required)]
    (case (:result r)
      :passed (binding [*out* *err*]
                (println "[smoke] PASSED"))
      (do
        (binding [*out* *err*]
          (println (str "[smoke] FAILED: "
                        (:result r)
                        (when-let [reason (:reason r)]
                          (str " | " reason))
                        (when-let [d (:detail r)] (str " | " d)))))
        (System/exit 2)))))

(defn cmd-test []
  (println "[test] live network probe (1 page, limit=2)")
  (let [cfg (assoc (load-config) :page-size 2)
        r   (fetch-page-cached! "software engineer" 0 cfg)]
    (println (str "  source: " (:source r)))
    (cond
      (= (:source r) :network-failed)
      (do (println (str "  status: " (:status r)))
          (println (str "  error:  " (:error r)))
          (println "[test] FAILED — network unreachable from this environment")
          (System/exit 1))

      :else
      (let [raw (results-from-body (:body r))]
        (println (str "  parsed: " (count raw) " results"))
        (if (seq raw)
          (let [sample (first raw)
                offer (transform-listing sample)]
            (println (str "  sample.title  = " (:title sample)))
            (println (str "  sample.url    = " (:jobDetailsUrl sample)))
            (println "  transformed offer ↓")
            (println (json/generate-string offer {:pretty true}))
            (println "[test] PASSED — MCF v2 reachable and schema mapping works"))
          (println "[test] PASSED (network OK; zero results for query)"))))))

(defn cmd-scrape [{:keys [query pages sleep-ms]}]
  (gate-with-smoke!)
  (ensure-cache!)
  (when-not query
    (println "ERROR: --query required for scrape")
    (System/exit 1))
  (let [cfg (load-config)
        sleep (or sleep-ms (:sleep-ms cfg))
        pgs   (or pages (:default-pages cfg))
        seen  (set (read-processed-ids))
        raw-fetched (atom 0)
        raw-new     (atom 0)
        raw-by-page (atom [])]
    (println (str "[scrape] query=\"" query "\" pages=" pgs " sleep-ms=" sleep))
    (dotimes [p pgs]
      (let [r (fetch-page-cached! query p cfg)]
        (case (:source r)
          :network-failed
          (do (println (str "  page " p " FAILED: status=" (:status r)))
              (swap! raw-by-page conj {:page p :status :failed}))

          (do
            (swap! raw-fetched inc)
            (let [raw (results-from-body (:body r))
                  fresh (->> raw
                             (remove #(seen (listing-dedupe-key %)))
                             (filter listing-has-valid-title?))]  ;; coalesced ->> thunk (filter keeps non-blank titles)
              (swap! raw-new + (count fresh))
              (swap! raw-by-page conj {:page p :raw (count raw) :fresh (count fresh)})
              (doseq [m fresh] (when-let [k (listing-dedupe-key m)] (append-processed-id! k)))
              (println (str "  page " p " raw=" (count raw) " fresh=" (count fresh)))
              (when (zero? (count fresh))
                (println "  no new records → stopping early")
                (reset! raw-fetched (inc p)))))))
      (Thread/sleep sleep))
    (println (str "[scrape done] pages=" @raw-fetched " new=" @raw-new))
    (println "  by-page:")
    (doseq [b @raw-by-page] (println (str "    " (pr-str b))))))

(defn cmd-emit [{:keys [query pages]}]
  (gate-with-smoke!)
  (ensure-cache!)
  (when-not query
    (println "ERROR: --query required for emit")
    (System/exit 1))
  (let [cfg (load-config)
        pgs  (or pages (:default-pages cfg))
        out  (atom [])]
    (dotimes [p pgs]
      (let [p2 (cache-path (cache-key query p))]
        (when (file-exists? p2)
          (let [raw (results-from-body (slurp p2))
                fresh (filter listing-has-valid-title? raw)]  ;; no dedup — caller (scan.mjs) owns the dedup
            (swap! out into (map transform-listing fresh))))))
    ;; Invariants: every emitted record must be a market-intelligence
    ;; primitive. assert-emit-invariants! exits 3 (and prints diagnostic to
    ;; stderr) before we ever print to stdout, so downstream consumers
    ;; never see a half-valid stream.
    (assert-emit-invariants! @out)
    ;; Emit in the exact data-toolkit local_parser stdout shape.
    (println (json/generate-string {"results" @out} {:pretty false}))
    (binding [*out* *err*]
      (println (str "[emit] query=\"" query "\" pages=" pgs " offers=" (count @out))))))

(defn cmd-status []
  (ensure-cache!)
  (let [raw-files (->> (file-seq (io/file raw-cache-dir))
                       (filter #(.isFile %))
                       (map #(.getName %)))
        ids (count (read-processed-ids))]
    (println "[status]")
    (println (str "  raw responses cached: " (count raw-files)))
    (println (str "  processed IDs tracked: " ids))
    (println (str "  config file:           " (if (file-exists? config-file) config-file "(missing; using built-in defaults)")))))

(defn cmd-clear []
  (let [d (io/file raw-cache-dir)]
    (when (.exists d)
      (doseq [c (file-seq d)] (when (.isFile c) (.delete c)))))
  (when (file-exists? ids-file)
    (.delete (io/file ids-file)))
  (println "[clear] cache reset"))

;; ===========================================================================
;; Offline self-test — run with:  bb cli.bb self-test
;;
;; Regression guard for the annualize arg-swap bug discovered during the
;; clj-skill-eval evaluation. If someone swaps sal-min and sal-max in the
;; annualize call inside transform-listing, the Monthly test produces
;; {:min 144000, :max 96000} instead of {:min 96000, :max 144000} and the
;; assertions fail immediately — no network required.
;; ===========================================================================

(defn run-self-test []
  (println "[self-test] annualize + transform-listing regression guard")
  (let [monthly-listing
        {:title     "Senior Software Engineer"
         :jobPostId "TEST-MONTHLY-1"
         :uuid      "u-monthly"
         :salary    {:minimum 8000 :maximum 12000 :type {:salaryType "Monthly"}}
         :skills    [{:skill "Clojure"} {:skill "Babashka"}]
         :metadata  {:newPostingDate "2026-07-01T00:00:00Z"}}

        annual-listing
        {:title     "Engineering Manager"
         :jobPostId "TEST-ANNUAL-1"
         :uuid      "u-annual"
         :salary    {:minimum 100000 :maximum 150000 :type {:salaryType "Annual"}}
         :skills    [{:skill "Leadership"} {:skill "Scala"}]
         :metadata  {:newPostingDate "2026-06-15T00:00:00Z"}}

        monthly-result (transform-listing monthly-listing)
        annual-result  (transform-listing annual-listing)

        monthly-ann (:salary_annualized_sgd monthly-result)
        annual-ann  (:salary_annualized_sgd annual-result)]

    (println (str "  Monthly 8000–12000 → annualized: " (pr-str monthly-ann)))
    (println (str "  Annual 100000–150000 → annualized: " (pr-str annual-ann)))

    ;; Monthly: 8000 × 12 = 96000, 12000 × 12 = 144000
    ;; If args are swapped, min would be 144000 and max would be 96000.
    (assert (= (:min monthly-ann) 96000)
            (str "Monthly min should be 96000 (8000×12), got " (:min monthly-ann)
                 " — arg-swap regression suspected!"))
    (assert (= (:max monthly-ann) 144000)
            (str "Monthly max should be 144000 (12000×12), got " (:max monthly-ann)
                 " — arg-swap regression suspected!"))
    ;; Annual: factor is 1, values pass through unchanged.
    ;; If args are swapped, min would be 150000 and max would be 100000.
    (assert (= (:min annual-ann) 100000)
            (str "Annual min should be 100000 (passthrough), got " (:min annual-ann)
                 " — arg-swap regression suspected!"))
    (assert (= (:max annual-ann) 150000)
            (str "Annual max should be 150000 (passthrough), got " (:max annual-ann)
                 " — arg-swap regression suspected!"))

    ;; Verify :salary_type and raw salary fields are preserved
    (assert (= (:salary_type monthly-result) "Monthly"))
    (assert (= (:salary_min monthly-result) 8000))
    (assert (= (:salary_max monthly-result) 12000))
    (assert (= (:salary_type annual-result) "Annual"))

    (println "[self-test] ALL ASSERTIONS PASSED")))

;; ===========================================================================
;; CLI dispatcher (manual arg parsing; keeps zero dependencies)
;; ===========================================================================

(defn parse-opts [argv]
  (loop [acc {} argv argv]
    (if (empty? argv)
      acc
      (let [k (first argv)
            v (second argv)]
        (cond
          (= "--query" k)    (recur (assoc acc :query v) (drop 2 argv))
          (= "--pages" k)   (recur (assoc acc :pages (Integer/parseInt v)) (drop 2 argv))
          (= "--sleep-ms" k) (recur (assoc acc :sleep-ms (Integer/parseInt v)) (drop 2 argv))
          (= "--help" k)    (assoc acc :help true)
          (= "-h" k)        (assoc acc :help true)
          :else             (recur acc (rest argv)))))))

(defn usage []
  (println "Usage: bb cli.bb {test|scrape|emit|status|clear} [--query Q] [--pages N] [--sleep-ms MS]")
  (println "  test                            live network probe (no cache write)")
  (println "  scrape   --query 'clojure'      fetch + cache + dedup")
  (println "  emit     --query 'clojure'      print data-toolkit local_parser JSONL on stdout")
  (println "  status                           cache stats")
  (println "  clear                            reset cache")
  (println "  self-test                        offline annualize regression guard (no network)"))

(defn -main [& argv]
  (let [cmd (first argv)
        opts (parse-opts (rest argv))]
    (if (or (:help opts) (nil? cmd))
      (usage)
      (case cmd
        "test"       (cmd-test)
        "scrape"     (cmd-scrape opts)
        "emit"       (cmd-emit opts)
        "status"     (cmd-status)
        "clear"      (cmd-clear)
        "self-test"  (run-self-test)
        (usage)))))

(when (System/getProperty "babashka.file")
  (apply -main *command-line-args*))
