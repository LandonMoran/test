package com.landonmoran.repro201tester

import android.os.Binder

/** Trivial throwaway bind target for the stress-test loop. Carries no logic. */
class StressService : Binder()
