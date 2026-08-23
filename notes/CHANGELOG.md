# Changelog

## [0.2.2](https://github.com/ScottKirvan/Pingd/compare/v0.2.1...v0.2.2) (2026-08-23)


### Bug Fixes

* use input flag instead of event_name for upload gating ([9d40019](https://github.com/ScottKirvan/Pingd/commit/9d4001970524fbd53bf2a3f4c288718d97f51f9e))

## [0.2.1](https://github.com/ScottKirvan/Pingd/compare/v0.2.0...v0.2.1) (2026-08-23)


### Bug Fixes

* wire build-android into release.yml via workflow_call ([1c437c0](https://github.com/ScottKirvan/Pingd/commit/1c437c0992aacfdc10301791802f867d61298bd0))

Includes PRs: [#68](https://github.com/ScottKirvan/Pingd/pull/68), [#69](https://github.com/ScottKirvan/Pingd/pull/69)

## [0.2.0](https://github.com/ScottKirvan/Pingd/compare/v0.1.1...v0.2.0) (2026-08-23)


### Features

* add temporary raw-sample/bucket-boundary debug overlay to ping-success graph ([57eb5f7](https://github.com/ScottKirvan/Pingd/commit/57eb5f7ddee4917e461a48adc0acd617df29d6ad))


### Bug Fixes

* add Play Store release pipeline with signed builds ([2ab0504](https://github.com/ScottKirvan/Pingd/commit/2ab05047d7d44ece60d3748764126c0f59f7474c))
* align success-sparkline bucket position with the latency graph's real-time axis ([c7bb454](https://github.com/ScottKirvan/Pingd/commit/c7bb4549644bc7a5f076e3a504eeaf68e2a8756b))
* anchor success-sparkline bucket width at the narrowest window ([e0820b9](https://github.com/ScottKirvan/Pingd/commit/e0820b99fcf638afa37359a0b91c46a5345733a8))
* credit the full history window once it is genuinely full ([f3ba234](https://github.com/ScottKirvan/Pingd/commit/f3ba234661bd209451a7faf9df1213d67f92ac7c))
* guarantee full coverage of windowed samples in success-sparkline ([040cec9](https://github.com/ScottKirvan/Pingd/commit/040cec9378fc143dca4b514f5eb8929da616b45f))
* make every history card's sparkline exactly the same width ([01fa260](https://github.com/ScottKirvan/Pingd/commit/01fa2607c8c84e1b580ca86d2c50385ea85993d9))
* make success-sparkline bucket width constant and add warm-up resolution ([f658fde](https://github.com/ScottKirvan/Pingd/commit/f658fdef2320b61d6be366124d6dd86877c5f80b))
* rename product to Ping'd, set applicationId to com.bojustudio.pingd ([b02474c](https://github.com/ScottKirvan/Pingd/commit/b02474cb70db90f055742e5f69ea8edb314f039f))
* render success-sparkline no-data buckets as misses, not gaps ([cea879b](https://github.com/ScottKirvan/Pingd/commit/cea879ba3d72eb8d7d73f6417f74e0559865c891))
* replace ping-success debug overlay with a separate, bucket-independent card ([7eb147d](https://github.com/ScottKirvan/Pingd/commit/7eb147db04d02faa7c3d42ee5231fcf89a1557d2))
* stop success-sparkline warm-up from fabricating failure dips ([793b418](https://github.com/ScottKirvan/Pingd/commit/793b4181ac9d34887dc0bbf61b2376efd1e506c4))
* update ApplicationIdTest to match com.bojustudio.pingd applicationId ([ec90b44](https://github.com/ScottKirvan/Pingd/commit/ec90b449624b36535e07c919afbcfe38bb98c8ac))

Includes PRs: [#54](https://github.com/ScottKirvan/Pingd/pull/54), [#56](https://github.com/ScottKirvan/Pingd/pull/56), [#57](https://github.com/ScottKirvan/Pingd/pull/57), [#58](https://github.com/ScottKirvan/Pingd/pull/58), [#59](https://github.com/ScottKirvan/Pingd/pull/59), [#60](https://github.com/ScottKirvan/Pingd/pull/60), [#62](https://github.com/ScottKirvan/Pingd/pull/62), [#64](https://github.com/ScottKirvan/Pingd/pull/64), [#65](https://github.com/ScottKirvan/Pingd/pull/65), [#66](https://github.com/ScottKirvan/Pingd/pull/66), [#67](https://github.com/ScottKirvan/Pingd/pull/67)

## [0.1.1](https://github.com/ScottKirvan/UplinkStatus/compare/v0.1.0...v0.1.1) (2026-08-18)


### Bug Fixes

* plot the latency graph on a fixed scale instead of autoscaling ([f7069fe](https://github.com/ScottKirvan/UplinkStatus/commit/f7069fe887f038eed2891ecbf7faa3c83d9e1bac))

Includes PRs: [#53](https://github.com/ScottKirvan/UplinkStatus/pull/53), [#55](https://github.com/ScottKirvan/UplinkStatus/pull/55)

## [0.1.0](https://github.com/ScottKirvan/UplinkStatus/compare/v0.0.0...v0.1.0) (2026-08-14)


### Features

* **app:** live scanner preview on the settings screen ([ddb3577](https://github.com/ScottKirvan/UplinkStatus/commit/ddb35775b48970d56d53b03fdf58e1578ef98120))
* **app:** live scanner preview on the settings screen ([83ff963](https://github.com/ScottKirvan/UplinkStatus/commit/83ff963f85e0a3f58a124a838be28062bb2f363b))
* color the history graphs and fix the latency axis direction ([e5bc54a](https://github.com/ScottKirvan/UplinkStatus/commit/e5bc54a6d98e66da4713c710040e207d3e021c0e))
* color the history graphs and fix the latency axis direction ([fe3f859](https://github.com/ScottKirvan/UplinkStatus/commit/fe3f859a1a8a2bb703732c5f407cfb54ab9d4bd9))
* default network scope to any connection, not WiFi only ([3554db5](https://github.com/ScottKirvan/UplinkStatus/commit/3554db5634ecb84f4aa30e50bab5ad067d2a8fa0))
* default network scope to any connection, not WiFi only ([a2ab89a](https://github.com/ScottKirvan/UplinkStatus/commit/a2ab89a26639985783e5ae0f971f184ad60b85a1))
* in-app ping-success and latency history graphs ([b7d6eae](https://github.com/ScottKirvan/UplinkStatus/commit/b7d6eae65ba568416ecd6f37657d6eeac475a45b))
* in-app ping-success and latency history graphs ([cae991e](https://github.com/ScottKirvan/UplinkStatus/commit/cae991e6fd77ae8595e6ce24f1ce1c221ba13586))
* in-app ping-success/latency history graphs, plus a failure-retry pacing fix for the reconnect battery drain ([acf395f](https://github.com/ScottKirvan/UplinkStatus/commit/acf395ff5861d33578fd218b166989fe33b7601c))
* keep recording history through DISABLED, mark master-toggle gaps ([3dc1d02](https://github.com/ScottKirvan/UplinkStatus/commit/3dc1d02e1f4d33d7c2a8b3c07813740dbd0b73e0))
* keep recording history through DISABLED, mark master-toggle gaps ([bd3cc4f](https://github.com/ScottKirvan/UplinkStatus/commit/bd3cc4f151388dabe643700782976be6593e645e))
* move pacing/window sliders under the graphs, show app version ([323b4c4](https://github.com/ScottKirvan/UplinkStatus/commit/323b4c43bd3f8ff5122855f0d0628ffc4312bdb6))
* move pacing/window sliders under the graphs, show app version ([2c9244b](https://github.com/ScottKirvan/UplinkStatus/commit/2c9244bab95683d69b751b556dd0cacea2e98c09))
* ping,ping,fake tracer cycle with a configurable step delay ([ebcb177](https://github.com/ScottKirvan/UplinkStatus/commit/ebcb1779e03cba081faf83d8763d5b2f9f52a1ad))
* ping,ping,fake tracer cycle with a configurable step delay ([a58b281](https://github.com/ScottKirvan/UplinkStatus/commit/a58b281fb6890e42418f3d8a1c05cd622d1f6578))
* shade sparkline gaps so they read as no-data, not a rendering bug ([689ddc6](https://github.com/ScottKirvan/UplinkStatus/commit/689ddc6188fc729ff1d6029fcf0619daadf332a4))
* shade sparkline gaps so they read as no-data, not a rendering bug ([f268b4a](https://github.com/ScottKirvan/UplinkStatus/commit/f268b4a49928574d8fc1cf81564369d1358668f7))
* shade sparkline gaps so they read as no-data, not a rendering bug ([15a1a99](https://github.com/ScottKirvan/UplinkStatus/commit/15a1a9950bfb03f84596200577a24f2d223a9b57))


### Bug Fixes

* anchor sparkline axis to the configured window, not the retained span ([635ab37](https://github.com/ScottKirvan/UplinkStatus/commit/635ab376704ba61c42b85284e9cd589097a952b7))
* anchor sparkline axis to the configured window, not the retained span ([a556613](https://github.com/ScottKirvan/UplinkStatus/commit/a556613463a850f7f4276c2c5d08e2515c8283ee))
* base success-sparkline resolution on elapsed time, not attempt count ([7085881](https://github.com/ScottKirvan/UplinkStatus/commit/70858811409f509fe0e94caf9780176dcab29330))
* base success-sparkline resolution on elapsed time, not attempt count ([50bca36](https://github.com/ScottKirvan/UplinkStatus/commit/50bca36051d53bd377062a761804c74feed049da))
* cap success sparkline bucket count by real sample density ([ccef0dc](https://github.com/ScottKirvan/UplinkStatus/commit/ccef0dc1663d56c268638fa3292b707d6806b5c8))
* decouple history-window retention from the display window ([dbc73a1](https://github.com/ScottKirvan/UplinkStatus/commit/dbc73a1071442bed861f734a73f2078cafc9e05d))
* decouple history-window retention from the display window ([4a07500](https://github.com/ScottKirvan/UplinkStatus/commit/4a075009e7963d1a432450c88927ef0e4badaf65))
* decouple history-window retention from the display window (issue [#39](https://github.com/ScottKirvan/UplinkStatus/issues/39)) ([5fbb607](https://github.com/ScottKirvan/UplinkStatus/commit/5fbb6079f3ca4414bcf46b601e3f8f3d9e374112))
* detect success-graph gaps from raw sample timestamps, not buckets ([985c983](https://github.com/ScottKirvan/UplinkStatus/commit/985c9838b38ba06c177571bad542b27260cdb198))
* detect success-graph gaps from raw sample timestamps, not buckets ([9bc3417](https://github.com/ScottKirvan/UplinkStatus/commit/9bc3417cd893643ca890b6852327cdbe1b83ea60))
* fire only the newest scheduled callback in the retry-pacing tests ([432d1fa](https://github.com/ScottKirvan/UplinkStatus/commit/432d1faadda4909b8b5f4daf363511e4c199c2fc))
* fire only the newest scheduled callback in the retry-pacing tests ([787505d](https://github.com/ScottKirvan/UplinkStatus/commit/787505dca5b67f42cb21271c6475d3b285c1c103))
* let the sparkline claim its own width instead of splitting 50/50 ([ee13f49](https://github.com/ScottKirvan/UplinkStatus/commit/ee13f4927e9d9a2cb12875371b84873aa5f5ebb5))
* let the sparkline claim its own width instead of splitting 50/50 ([0a06687](https://github.com/ScottKirvan/UplinkStatus/commit/0a06687e17b305b3d556746370fd21541d9d5b95))
* pace failed-probe retries with a 250ms floor instead of retrying inline ([4cb7200](https://github.com/ScottKirvan/UplinkStatus/commit/4cb7200bf3d89f7a5d34dc2f73a3aba34d9a7493))
* pace failed-probe retries with a 250ms floor instead of retrying inline ([270bd6a](https://github.com/ScottKirvan/UplinkStatus/commit/270bd6a71fa33bc61d2a5b5642f52bb478129ff4))
* raise the sample cap so a reconnect failure burst can't evict it ([0c5b84f](https://github.com/ScottKirvan/UplinkStatus/commit/0c5b84f82d463703bce1c10a4dc0a07b2318492b))
* raise the sample cap so a reconnect failure burst can't evict it ([dcfbab4](https://github.com/ScottKirvan/UplinkStatus/commit/dcfbab45f785ddffe2273a67877c930934446f3d))
* settings-preference changes to a running probe cycle were dropped ([aea9d06](https://github.com/ScottKirvan/UplinkStatus/commit/aea9d06c6404bfa3a28904aa4ee03a85c18c8527))
* settings-preference changes to a running probe cycle were dropped ([0f042fd](https://github.com/ScottKirvan/UplinkStatus/commit/0f042fd8fd05bb70d5f3b64331f618a1a265fa4a))
* use fixed colors for the ping-success gradient sweep ([f871cc4](https://github.com/ScottKirvan/UplinkStatus/commit/f871cc4457e8fe34c02622a4dd09915c9859ed78))

Includes PRs: [#33](https://github.com/ScottKirvan/UplinkStatus/pull/33), [#34](https://github.com/ScottKirvan/UplinkStatus/pull/34), [#35](https://github.com/ScottKirvan/UplinkStatus/pull/35), [#36](https://github.com/ScottKirvan/UplinkStatus/pull/36), [#47](https://github.com/ScottKirvan/UplinkStatus/pull/47), [#48](https://github.com/ScottKirvan/UplinkStatus/pull/48), [#49](https://github.com/ScottKirvan/UplinkStatus/pull/49), [#50](https://github.com/ScottKirvan/UplinkStatus/pull/50), [#51](https://github.com/ScottKirvan/UplinkStatus/pull/51), [#52](https://github.com/ScottKirvan/UplinkStatus/pull/52)

## 0.0.0 (2026-08-05)


### Features

* **app:** device-testing fixes, settings-lock, and status line (Stage 8) ([4c36681](https://github.com/ScottKirvan/UplinkStatus/commit/4c36681438b454b197d0155e6f1d73dfc0153f51))
* **app:** drive network-scope matching from real ConnectivityManager (Stage 4) ([9ad5064](https://github.com/ScottKirvan/UplinkStatus/commit/9ad50649ea804fdb8a0f8975a9aa02cee41d092f))
* **app:** real DataStore-backed preferences + settings screen (Stage 3) ([17f89e2](https://github.com/ScottKirvan/UplinkStatus/commit/17f89e2bd4876b717eb4f63cd7c86cf97813626f))
* **app:** wire foreground service + notification to core state machine (Stage 2) ([ba2512b](https://github.com/ScottKirvan/UplinkStatus/commit/ba2512b7d4c485a6a10e9d6f16187eb59c0693fa))
* **core:** add probe + tracer/ack state machine (Stage 1) ([f0196d0](https://github.com/ScottKirvan/UplinkStatus/commit/f0196d043ddc0bdc1eab97d64a23383e58891bd8))
* scaffold Android project with Compose and CI (Stage 0) ([2c142b7](https://github.com/ScottKirvan/UplinkStatus/commit/2c142b7d154ea2eaf3af3ac93ce9e2c40360e4d8))


### Bug Fixes

* add apply-staged-notes job and pre-release-staging workflow ([2f5a826](https://github.com/ScottKirvan/UplinkStatus/commit/2f5a8269ba56c77743f055307dc5d83d9c42f812))
* add href to last-commit badge link pointing to commits/main ([96671fb](https://github.com/ScottKirvan/UplinkStatus/commit/96671fbcb2b3493ada7a7e8b7ef4ee6bef304e94))
* **app:** distinct notification text for DNS vs generic freeze (Stage 5) ([5795233](https://github.com/ScottKirvan/UplinkStatus/commit/5795233b3ed8f1c50cfcabad1f68fce188f6c228))
* **app:** match network scope against every connected network ([d59651c](https://github.com/ScottKirvan/UplinkStatus/commit/d59651ce82935a5f6d8870f19e78b8331768483a))
* **app:** match network scope against every connected network ([4ec90d6](https://github.com/ScottKirvan/UplinkStatus/commit/4ec90d6f9024e0ea556a52251f6382fbadf6dfeb))
* **app:** persist a settings change before restarting the service ([58ab9c3](https://github.com/ScottKirvan/UplinkStatus/commit/58ab9c3f798b7aa70525db326ac31f5139672947))
* **app:** post a foreground notification immediately in onStartCommand ([fae746b](https://github.com/ScottKirvan/UplinkStatus/commit/fae746bea99aa6054b262271898ad384515c5357))
* **app:** re-read connectivity when the location permission changes ([2dc4b0b](https://github.com/ScottKirvan/UplinkStatus/commit/2dc4b0b2165fc9b265625561985f43034cd4243a))
* **app:** report only confirmed states on the in-app status line ([f89bb67](https://github.com/ScottKirvan/UplinkStatus/commit/f89bb6744141b4d78a831d61b403b27e058ed8f8))
* **app:** stop deriving a real visibility verdict from "no network report yet" ([ab80b4b](https://github.com/ScottKirvan/UplinkStatus/commit/ab80b4b6efe650027ae933fc02d52b61e8877636))
* **core:** correct tracer to a ping-pong sweep instead of a wrap ([9bbed3b](https://github.com/ScottKirvan/UplinkStatus/commit/9bbed3b5d4e8711289216fd9568daba3a82f491c))
* **core:** stop the probe cycle without queueing behind its own retry loop ([abcd81c](https://github.com/ScottKirvan/UplinkStatus/commit/abcd81cba8adcda5db6f54f5e9e0605018853fbd))
* correct Discord invite link in README ([0e29eed](https://github.com/ScottKirvan/UplinkStatus/commit/0e29eed657127226ad0c21bad106370339ef14b4))
* correct Discord invite link in README ([c0688ce](https://github.com/ScottKirvan/UplinkStatus/commit/c0688ce479515a7aeeeba35f9a369a40ffd1fcfe))
* migrate starline badge to self-hosted GitHub Action ([4271cae](https://github.com/ScottKirvan/UplinkStatus/commit/4271caea9df2f0c5687a69e370fa4b1c544e609b))
* re-read connectivity when the location permission changes (unconfirmed on hardware) ([9ecdffc](https://github.com/ScottKirvan/UplinkStatus/commit/9ecdffcecac93efdf865ee362a3b21bf2021325f))

## Changelog
>[!NOTE]
> This file and it's version format is automatically 
> generated by [Please-Release](https://github.com/googleapis/release-please-action), 
> and adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
