# Milestones

1. Go/No-Go for Storage/Compute + Arrow
   - TODO Large TPC-H SF (1?), running remotely, hot/cold - performance numbers, billing, monitoring, bottlenecks
     - TODO Larger TPC-H scale factors (SF10) - check ingest + query
       - linear growth in aggregate queries, sub-linear in accesses
     - TODO Concurrency - check running ingest + multiple queries in parallel
       - DONE Running multiple TPC-H nodes in the cloud - check everything works same as locally
         works with SF0.01, super-linear ingest issues currently prevent SF0.1
     - TODO Check queries too slow, possible solutions
       - Predicate push-down
         - DONE for joins passing bloom filters
     - TODO Check cold caches, possible solutions:
       - Tiered caching
   - TODO Join order benchmarking - WatDiv, graph
     - WCOJ? see worst-case optimal hash join paper.
       - constructing/storing hash indices?
   - TODO Dealing with updates over time - historical dataset (TS Devices)
     - DONE ingest bench
     - DONE Temporal range predicates in logical plan scan
     - TODO test the queries using our logical plan
   - TODO Scalable temporal indexing + querying
     - Exercise temporal side, TPC-BiH
     - DONE SQL:2011 period predicates in logical plan expressions:
       - overlaps, equals, contains, (immediately) precedes, (immediately) succeeds.
     - DONE Add interval types and arithmetic?
     - How far does the current kd-tree take us? Need different approach or fixable as initial cut?
   - DONE Bigger than local node databases
2. Core2 as something keen users can play with
   - Features, functionality
     - Higher-level queries
       - TODO Basic EDN Datalog - compiling EDN Datalog down to logical plan
         - DONE :find, :where (triple joins),
         - DONE :order-by, :limit, group-by/aggregates
         - known predicates
         - DONE :in clauses, parameterisation
     - TODO as-of API (within query)
     - TODO basic WatDiv
   - Remote-first API
   - 'Running it' story - currently clone + lein repl, want a JAR
   - Documentation (README)
   - CI
3. Core2 as a viable alternative to Crux
   - Arrowification - removal of type-ids
   - Features, functionality
     - Eviction
     - Full EDN Datalog
       - Rules, or-joins, not-joins, self-joins etc
       - Query planning
       - Pull
       - Nested queries
     - Bitemporal features, interval algebra.
       - Expose valid-time
     - Log mirror
     - SQL.
       - SQL:92/PartiQL (dynamic data?)
       - SQL:2011 (temporal)
       - SQL:2016? (nested data, JSON)
       - Graph queries in SQL - SQL:2022, GQL (subset), SQL/PGQ
     - Multi-way WCOJ hash joins?
     - More logs/object-stores
       - Kinesis
       - GCP Pub/Sub and Cloud Storage.
       - GCP benchmarks.
       - Azure EventHubs and Blobs.
       - Azure benchmarks.
       - JDBC log, object store.
     - How much more of Crux should Core2 pull in? Requires discussion
       - Match
       - Speculative txs
       - Tx fns
       - Support lists/cardinality-many?
   - Migration from Classic
   - Deployment, monitoring story
   - Documentation, marketing

Ideas/Risks:
- Semi option C-ification - column groups
- Do we need to include tx-id somehow in the temporal index?
- Postgres wire protocol
- Temporal range queries
- Temporal joins
- Logical plan needs error handling if we're going to expose it to users. Also, it's EDN.