# Third-Party Notices / 第三者コンポーネントのライセンス

Aiphone のオリジナルコードには Apache License 2.0 を適用します。ただし、第三者コンポーネントは、それぞれのライセンス条件に従います。本ファイルの一覧は現時点で把握している主要な直接依存関係であり、依存関係全体の完全なライセンス調査結果ではありません。

## 主な依存関係

| Component | License / status | Official source |
|---|---|---|
| AndroidX Core KTX / AppCompat / Activity KTX / Lifecycle Runtime KTX | Apache-2.0 | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| Material Components for Android | Apache-2.0 | https://github.com/material-components/material-components-android |
| OkHttp | Apache-2.0 | https://github.com/square/okhttp/blob/master/LICENSE.txt |
| Android Hidden API Bypass | Apache-2.0 | https://github.com/LSPosed/AndroidHiddenApiBypass |
| priv-kit (`io.github.priv-kit:priv-ui:0.17.6`) | **License needs confirmation** | https://github.com/priv-kit/priv-kit |

## Important notes

- Apache-2.0 allows commercial use, modification, and redistribution subject to its conditions. When redistributing covered code or binaries, include the required license and applicable notices, retain existing relevant notices, and mark modified files as required by the license.
- The upstream priv-kit repository's top-level file listing did not show a clear `LICENSE` file when checked on 2026-10-13. This does not by itself prove that no license exists, but the repository's terms and the published Maven artifact's licensing metadata should be confirmed with the maintainer before redistributing binaries or copied source. Aiphone's Apache-2.0 license does **not** grant rights to priv-kit or override its terms.
- Do not assume this table is exhaustive. Review the resolved Gradle dependency graph (including transitive dependencies and any SDK/runtime components) before publishing an APK/AAB. Include all required third-party license texts and attribution notices in the distribution.
- This notice is informational and is not legal advice.

## Relevant links

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [Google Gemini API Additional Terms](https://ai.google.dev/gemini-api/terms)
- [priv-kit repository](https://github.com/priv-kit/priv-kit)
