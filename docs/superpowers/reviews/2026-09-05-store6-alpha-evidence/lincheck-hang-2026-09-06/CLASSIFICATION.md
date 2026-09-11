# Lincheck "execution has hung" — classification (2026-09-11)

**Sighting.** `MutationJournalLincheckTest.inMemoryJournalTransactions_areLinearizable` failed with
`LincheckAssertionError: The execution has hung` after 395.6 minutes in the local full-suite run
of the alpha integration worktree on 2026-09-06 (hostname Matts-MacBook-Pro.local, JDK 11.0.31,
36 test classes / 306 tests in one shared JVM, Lincheck 2.39). The XML is preserved beside this
file; `failure.txt` carries the Lincheck trace and thread dump verbatim.

**Evidence.**
- The journal storage under test (`mutations/src/commonMain/.../storage/`) is byte-identical
  between the failing revision and the `store6` head (`b123c95a`), where the same fixed-seed
  scenarios passed on every uncached hosted execution (last: 2026-08-30, 144 min).
- Thread dump: thread 2 is inside native class loading (`Class.forName0`) called from Lincheck's
  own `Injections.onMethodCall` hook at `InMemoryMutationJournalStorage.transaction(:27)`;
  thread 1 never received its first turn (`ManagedStrategy.onThreadStart -> awaitTurn`); thread 0
  is parked at a managed switch point inside `HashMap.resize` during `JournalState.mutableCopy`.
  No thread is inside journal logic that could loop.
- The revision added test classes that load additional engine classes before the Lincheck class
  runs in the shared JVM; the subsequent commit's own message reports 12 oversized-method
  instrumentation failures and three heap exhaustion errors in that same run.

**Classification.** JVM-state-dependent Lincheck instrumentation/class-loading stall under the
managed scheduler (a class-initialization wait on a thread the strategy has parked), surfaced by
the hang detector. Not a journal linearizability failure. Disposition: **disproven as a storage
defect; accepted as an instrumentation artifact**, remedied by running the Lincheck class in its
own JVM (dedicated test task) so its class-loading state no longer depends on earlier tests.
`lincheck.instrumentAllClasses=true` (added 2026-09-06 as a workaround) is reverted: it made the
hosted execution exceed the 360-minute cap (run 34032803300) by instrumenting every loaded class.

**Rule observed.** The failing run was not rerun. The corrected configuration's first execution
is recorded as new evidence, not as a rerun of this one.

## Addendum 2026-09-11 — hosted dry runs of the sharded gate

Two `workflow_dispatch` runs of `store6-full-jvm.yml` at `938b845b` (plan = 101 scenarios, four
shards, digest `353a057d5f715bc8`), both first attempts, neither rerun:

| Run | Shard runner | Outcome |
| --- | --- | --- |
| 34566976609 | ubuntu-latest | **Success.** jvmTest lane 1m48s; shards 1–4 in 36m16s / 46m27s / 50m05s / 51m14s; `validation-evidence` accepted every record (digest, iteration census, union 0..100). Whole gate: 51 minutes wall. |
| 34566978279 | macos-latest | **Failure.** Shards 1, 2 and 3 each reported `The execution has hung, see the thread dump` on iteration 1, 22–23 seconds after the iteration started, with an empty journal and the first actors marked `<hung>`; shard 4 passed 25 iterations in 20 minutes; the jvmTest lane passed. |

Excerpts are beside this file under `hosted-dry-runs-2026-09-11/`.

**Mechanism.** Lincheck 2.39 reports this failure from its per-invocation wall-clock deadline
(`CTestConfiguration.DEFAULT_TIMEOUT_MS = 20000`, class `strategy.TimeoutFailure`): the thread
dumps show one thread still executing inside Lincheck's own call bookkeeping
(`Injections.onMethodCall`) while the other two wait for their turn — an invocation that had not
finished within 20 seconds, not a deadlock. The first invocation of a cold JVM pays class loading
and on-first-use bytecode transformation; on the macOS runners that exceeded 20 seconds in three
of four shards, on ubuntu-latest in none, and locally in none. This is the same mechanism as the
2026-09-06 local sighting (a `Class.forName` frame inside `onMethodCall` while the machine was
loaded). `Options.invocationTimeout` is `internal` in Lincheck 2.39, so the deadline cannot be
raised from test code without constructing the configuration through internal API.

**Disposition.** The release gate runs the shards on `ubuntu-latest`, where the complete sharded
suite has now executed once with every guard accepting the evidence. `macos-latest` is **not
adopted** for the gate. Follow-up (not release-blocking): re-evaluate the runner and the
invocation deadline when a Lincheck version exposes the timeout publicly. The storage-defect
classification above stands: no shard produced a linearizability failure.
