(ns causal-tree.shared
  (:require
   [causal-tree.util :as u :refer [<< guid]]
   [clojure.spec.alpha :as spec]
   [clojure.spec.gen.alpha :as gen]))

; node:   the smallest unit of causation. unique Id, Value, Cause.
; nodes:  a map of all nodes by their Ids. This is the canonical store for all nodes in a tree.
; yarn:   CACHE - a time ordered vector of nodes from a specific site. These speed up weft generation and double the size of the tree.
; weft:   a path through 1 or more yarns used to generate a new tree representing any previous state of the tree.
; weave:  CACHE - a partially ordered vector of all nodes. This makes reading the tree O(n), but incresaes inserts from O(1) to O(n).
; causal-tree:  a store for all of the above

; This is an implmentation of a Causal Tree CRDT in CLJ(S)
; Awesome blog post with graphics and Swift impl: http://archagon.net/blog/2018/03/24/data-laced-with-history/
; Original paper: http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.627.5286&rep=rep1&type=pdf
; Follow up paper (more detailed impl): https://www.dropbox.com/spec/6go311vjfqhgd6f/Deep_hypertext_with_embedded_revision_co.pdf?dl=0

(def types #{::map ::list}) ; ::rope
(def speical-keywords #{::delete})
(def root-id [0 "0"])
(def root-node [root-id nil nil])

(defn gen-string [length]
  (gen/fmap #(apply str %) (gen/vector (gen/char-alpha) length)))

(spec/def ::type types)
(spec/def ::lamport-ts nat-int?) ; AKA the index in a yarn
; TODO: should a wall-clock-ts be added? If a central Datomic DB is used then nodes will get a wall clock ts based on when they were synced to the server...
(spec/def ::basic-guid (spec/with-gen (spec/and string? #(or
                                                          (= (count %) u/site-id-length)
                                                          (= % "0")))
                                      #(gen-string u/site-id-length)))
(spec/def ::site-id ::basic-guid)
(spec/def ::id (spec/tuple ::lamport-ts ::site-id))
(spec/def ::key (spec/or :k keyword?
                         :s string?))
(spec/def ::cause (spec/or :previous-list-item ::id
                           :map-key ::key))
(spec/def ::value (spec/or :special-k speical-keywords
                           :c char?
                           :s string?
                           :k keyword?
                           :n number?
                           :ct ::causal-tree))

; AKA an atom in CT parlance.
(spec/def ::node (spec/cat :id ::id
                           :cause ::cause
                           :value ::value))
(spec/def ::root (spec/cat :id #{root-id}
                           :cause nil?
                           :value nil?))

(spec/def ::nodes (spec/map-of ::id (spec/tuple ::cause ::value)))

(spec/def ::yarn (spec/coll-of ::node)) ; site specific time sorted vector
(spec/def ::yarns (spec/map-of ::site-id ::yarn)) ; map of yarns keyed by site-id

(spec/def ::weave (spec/or
                   :list-weave (spec/coll-of ::node) ; ordered vector of operations in the order of their output
                   :map-weave (spec/map-of ::key (spec/coll-of ::node)))) ; map of ordered vectors corresponding to keys

(spec/def ::causal-tree (spec/keys :req [::nodes ::lamport-ts ::site-id]
                                   :opt [::yarns ::weave]))

(defn node
  ([[k v]] ; maps the keys / values in the ::nodes map back to nodes
   (into [k] v))
  ([lamport-ts site-id cause value]
   [[lamport-ts site-id] cause value]))
(spec/fdef node
           :args (spec/or
                  :arity-1 (spec/cat :node-kv-tuple (spec/tuple ::id seqable?))
                  :arity-5 (spec/and
                            (spec/cat :lamport-ts ::lamport-ts
                                      :site-id ::site-id
                                      :cause ::cause
                                      :value ::value)
                            #(> (:lamport-ts %) (first (:cause %))))) ; node ts must be more than cause ts
           :ret ::node
           :fn (spec/and #(not= (first (:ret %)) (get-in % [:args :cause])))) ; cause can't equal node-id

(defn spin
  "Spin yarn(s)...
  Returns a causal-tree with updated yarn index. If a node is passed
  only the yarn relating to that node will be updated. Otherwise the
  entire tree will be traversed and (re)indexed."
  ([causal-tree]
   (loop [ct1 causal-tree
          sorted-nodes (pmap node (sort (::nodes causal-tree)))]
     (if (empty? sorted-nodes)
       ct1
       (recur (spin ct1 (first sorted-nodes))
              (rest sorted-nodes)))))
  ([causal-tree node]
   (let [site-id (second (first node))]
     (if-let [yarn (get-in causal-tree [::yarns site-id])]
       (if (> (ffirst node) (ffirst (last yarn))) ; compare lamport timestamps
         (update-in causal-tree [::yarns site-id] conj node)
         (update-in causal-tree [::yarns site-id] u/insert node)) ; u/insert is expensive. Avoid it.
       (assoc-in causal-tree [::yarns site-id] [node])))))

(defn insert
  "Inserts an arbitrary node from any site and any point in time. If the
  node's ts is greater than the local ts then the local ts will be
  fastforwared to match."
  [causal-tree node weave-fn]
  (if-let [existing-node-body (get-in causal-tree [::nodes (first node)])]
    (if (= (rest node) existing-node-body)
      causal-tree
      (throw (ex-info "This node is already in the tree and can't be changed."
                      {:causes #{:append-only :edits-not-allowed}
                       :existing-node (cons (first node) existing-node-body)})))
    (if (and (not (spec/valid? ::key (second node))) ; if the cause is a ::key we can ignore this check
             (not (get-in causal-tree [::nodes (second node)])))
      ; TODO: is this needed? parallel adjacent inserts might be possible without this.
      (throw (ex-info "The cause of this node is not in the tree."
                      {:causes #{:cause-must-exist}}))
      (let [ct2 (if (> (ffirst node) (::lamport-ts causal-tree))
                  (assoc-in causal-tree [::lamport-ts] (ffirst node))
                  causal-tree)
            ct3 (assoc-in ct2 [::nodes (first node)] (rest node))
            ct4 (spin ct3 node)
            ct5 (weave-fn ct4 node)]
        ct5))))

(defn append
  "Similar to insert, but automatically calculates node id based on the
  local site-id and lamport-ts."
  [causal-tree cause value weave-fn]
  (let [ct2 (update-in causal-tree [::lamport-ts] inc)
        node (node (::lamport-ts ct2) (::site-id ct2) cause value)]
    (insert ct2 node weave-fn)))

(defn refresh-ts
  "Refreshes the ::lamport-ts to make sure it's the max value in the tree.
  Expects ::yarns cache to be up to date and sorted."
  [causal-tree]
  (->> (::yarns causal-tree)
       (reduce #(max %1 (ffirst (last (last %2)))) 0)
       (assoc causal-tree ::lamport-ts)))

(defn yarns->nodes
  "Replaces the ::nodes map of tree with the nodes in the tree's ::yarns."
  [causal-tree]
  (->> (::yarns causal-tree)
       (reduce #(concat %1 (second %2)) [])
       (reduce #(assoc %1 (first %2) (rest %2)) {})
       (assoc causal-tree ::nodes)))

(defn refresh-caches
  "Replaces everything but ::nodes and ::site-id with refreshed caches
   of ::weave ::yarns etc. Useful when loading in ::nodes."
  [causal-tree weave-fn]
  (->> causal-tree
       (spin)
       (refresh-ts)
       (weave-fn)))

(defn weft
  "Returns a causal-tree that is a sub tree of the original up to the
  specified Ids. Specify one specific ::id per site you want included
  in the weft. Only the yarns of the site-ids contained in the ids in
  the args will be considered in the returned sub-tree. This is how
  you time travel. Combinations of Ids that do not preserve causality
  are invalid and will result in gibberish trees."
  ; TODO: throw on ids that do not preserve causality. This likely invloves writing a O(n) un-weave function that can rollback a weave to the specified weft and throw if the rollback breaks causality...
  [causal-tree initial-ids new-causal-tree-fn weave-fn]
  (let [filtered-ids (filter #(not= root-id %) initial-ids)]
    (loop [new-ct (new-causal-tree-fn)
           id (first filtered-ids)
           more-ids (rest filtered-ids)]
      (if id
        (recur (as-> (get-in causal-tree [::yarns (second id)]) $
                     (take-while #(not= id (first %)) $)
                     (vec $)
                     (conj $ (node [id (get-in causal-tree [::nodes id])]))
                     (assoc-in new-ct [::yarns (second id)] $))
               (first more-ids) (rest more-ids))
        (-> new-ct
            (assoc ::site-id (::site-id causal-tree))
            (assoc ::lamport-ts (apply max (pmap first filtered-ids)))
            (yarns->nodes)
            (weave-fn))))))

; TODO: should this take whole trees or a tree and nodes?
;   Nodes are simpler, can be sorted, and merged in with O(n*m)
;   m being the number of nodes in the merge. It's likely that
;   there will be duplicate nodes either way, so a diff will
;   always need to be calculated...
(defn merge-trees
  "Merges two or more causal-trees into one."
  ([weave-fn causal-tree1 causal-tree2 & more]
   (apply merge-trees weave-fn
          (merge-trees weave-fn causal-tree1 causal-tree2)
          more))
  ([weave-fn causal-tree1 causal-tree2]
   (println "TODO")))