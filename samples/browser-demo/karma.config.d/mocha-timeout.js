// Mocha's default 2 s per test is too tight for the slow macOS runner.
config.client = config.client || {};
config.client.mocha = Object.assign(config.client.mocha || {}, { timeout: 30000 });
