# RTL Material 3 UI modernization

- Material `enableEdgeToEdge()` and system-bar icon luminance follow saved appearance mode.
- Root Scaffold applies and consumes its `innerPadding` once; the scrollable screen content adds 16dp spacing.
- Modernized common 16dp rounded panels, 1dp soft borders, icon-bearing search and password inputs.
- Customer and package cards preserve repository data and existing selection callbacks.
- Management hero uses actual customer and package totals.
- Appearance selector preserves DataStore actions; security form preserves SecurityViewModel actions.
- Backup section preserves URI import/export, Drive API, retention consent and scheduler semantics; switch operations require the same phrase/authorization checks as explicit activation.
- The appearance selection does NOT change the Android launcher icon at runtime. The launcher is controlled by system-provided icon resources.

No Room schema, DAO, ViewModel, accounting operation, or data migration was changed by this pass.
A complete Android Gradle build must be run on a configured SDK or the owner's Code On The Go environment.
