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
