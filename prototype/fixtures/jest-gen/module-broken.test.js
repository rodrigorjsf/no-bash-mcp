// Axis 5 for jest, the GENUINE no-test-owner case: a throw at module top-level.
// jest cannot even collect tests here, so it reports this file's testResults[]
// entry with assertionResults: [] and a file-level execerror message.
//
// NOTE (real-report finding): a beforeAll throw does NOT produce this shape in
// jest 29 — it attributes the hook failure to EACH test in the suite (see
// setup-broken.test.js). Only a collection/load failure has no test owner.
throw new Error('module failed to load: simulated collection-time failure with no test owner');
