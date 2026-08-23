<img src=".github/images/hero-light.svg" alt="Store" width="100%"/>

# Store

[![codecov](https://codecov.io/gh/matt-ramotar/Store6/branch/store6/graph/badge.svg?token=OUZ4TB2VXW)](https://codecov.io/gh/matt-ramotar/Store6)

Store is a Kotlin Multiplatform library for reading and writing data that lives in more than one
place: a network, a local database, and memory. You describe a key and a fetcher. Store handles
single-flighting, staleness, invalidation, and bounded memory, and it covers each default behavior
with a conformance test you can read.

Store 5 is the current release line. Store 6, the next major, is in development in this repository
and will ship under separate `store6-*` coordinates, so Store 5 keeps publishing through the 6.x
major. Nothing 6.x is published yet: the first release will be `6.0.0-alpha01`, with
`store6-mutations` experimental. [STABILITY.md](STABILITY.md) sets out the API tiers, deprecation
cycle, and release cadence; [ROADMAP.md](ROADMAP.md) is the public roadmap.

## Documentation

Guides and API reference: [store.mobilenativefoundation.org](https://store.mobilenativefoundation.org)

Until the alpha ships, the Store 6 quickstart lives in-repo at
[docs/store6/quickstart.md](docs/store6/quickstart.md).

## Getting Help

Ask in the [#store](https://kotlinlang.slack.com/archives/C06007Z01HU) channel on the Kotlin Slack.

## Contributing

We welcome contributions; see [CONTRIBUTING.md](CONTRIBUTING.md).

## Backed By

<div style="display: flex; align-items: center; gap: 20px;">
    <img src=".github/images/mobile-native-foundation.png" alt="Mobile Native Foundation" width="200"/>
    <img src=".github/images/kotlin-foundation.png" alt="Kotlin Foundation" width="200"/>
</div>

## License

Copyright (c) 2024 Mobile Native Foundation. Licensed under the Apache License, Version 2.0; see
[LICENSE](LICENSE).
