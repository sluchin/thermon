# プロジェクト設定

Kotlin で作る Android アプリ。AWS の DynamoDB に保存された温度・湿度のデータを、Cognito の ID プールの未認証ロールで読み取って、表示する (AWS のクライアント)。
姉妹プロジェクト [esp32-thermo-system](https://github.com/sluchin/esp32-thermo-system) (ESP32 のノードとゲートウェイ) が、データを AWS IoT Core 経由で、DynamoDB に保存する。AWS 側の準備は、このリポジトリの `AWS_DYNAMODB.md`。

**Android Studio は使わない。** ビルド、テスト、整形、静的解析、ドキュメントの生成は、全て、コマンドラインで、Docker の中で実行する。環境構築の詳細と、検証の記録は、`SETUP.md`。

## コマンド

### Docker (推奨)
- `docker compose run` には、必ず `--rm` を付ける (終わったコンテナを、残さないため)。
- `UID` と `GID` を、シェルでエクスポートしておく (生成物が、root 所有にならないように。`SETUP.md` の「2. ホストのユーザー ID を環境変数にエクスポートする」)。
- イメージのビルド: `docker compose build` (`Dockerfile` を変えたときも)
- ビルド (デバッグ APK): `docker compose run --rm build` (`app/build/outputs/apk/debug/app-debug.apk`)
- 単体テスト: `docker compose run --rm test`
- カバレッジ (`core` の行と分岐が 100%): `docker compose run --rm coverage`
- 整形の確認: `docker compose run --rm format` / 整形して書き換える: `docker compose run --rm format-fix`
- 静的解析 (detekt と Android Lint): `docker compose run --rm analyze`
- ドキュメント (Dokka): `docker compose run --rm docs` (`core/build/dokka/html/index.html`)
- クリーン: `docker compose run --rm clean` (`build/`、`.gradle/`、`.kotlin/` を消す。Gradle のキャッシュは、消さない)
- 開発シェル: `docker compose run --rm dev`
- エミュレータ (KVM。`export KVM_GID=$(getent group kvm | cut -d: -f3)` が要る。イメージは約 10.7 GB): `docker compose build emulator` → `docker compose up -d emulator` → `docker compose exec emulator scripts/emulator-wait.sh` → `docker compose exec emulator adb install -r app/build/outputs/apk/debug/app-debug.apk`。画面は <http://localhost:6080/vnc.html>、画像にするときは `adb exec-out screencap -p > build/emulator-screen.png`。止めるのは `docker compose down` (`SETUP.md` の「エミュレータ (Docker)」) (中で `./gradlew ...` を、直接実行できる)

### 実機への転送 (ホストの adb)
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` (ホストに adb を入れる。`SETUP.md` の「実機へのインストール」と「アプリを動かして確認する」。実機で確認済み)

### クラウドのセッション (claude.ai/code) で Docker を使うとき

**通常の `docker compose build` と `docker compose run` は、使わない** (curl の終了コード 60 で失敗する。理由は、下)。代わりに、`scripts/cloud-docker.sh` を使う (`SETUP.md` の「クラウドのセッション」)。

```bash
scripts/cloud-docker.sh build          # 開発用のイメージ (thermon-dev) を作る (プロキシと CA の設定を足す)
scripts/cloud-docker.sh run build      # docker compose run --rm build (test、coverage、format、analyze、docs も同じ)
scripts/cloud-docker.sh start          # Docker のデーモンを起動する (起動済みなら、何もしない)
```

- クラウドの VM には、Docker (`dockerd`) が入っているが、デーモンは、自動では起動しない。SessionStart フック (`.claude/settings.json`) が、セッションの開始のときに、`scripts/cloud-docker.sh hook` で起動する (クラウドのときだけ)。起動していないときは、`scripts/cloud-docker.sh start`。ログは、`/tmp/dockerd.log`。
- VM の外への通信は、全て、Anthropic のプロキシを通る。通信できるドメインは、環境の「Network access」で決まる (標準の Trusted の一覧にないドメインは、「Custom」で足す。`dl.google.com` と `maven-central.storage-download.googleapis.com` が要る)。
- **curl の終了コード 60 の原因** (2026-10-11 に確認): VM の通信は、プロキシと、そのプロキシの CA 証明書を使うが、**コンテナは、どちらも引き継がない**。
  - VM には、`HTTPS_PROXY=http://127.0.0.1:<ポート>` が設定されている。**ポートは、セッションの途中でも変わる** (例: 37451、45999) ので、固定で書かず、実行のたびに `$HTTPS_PROXY` から読む。`127.0.0.1` は、コンテナの中では、コンテナ自身を指す。
  - CA 証明書は、`/root/.ccr/ca-bundle.crt` にある (`SSL_CERT_FILE` などが指す)。VM のシステムの信頼ストアには、入っていない。
- **`cloud-docker.sh` がしていること**: `Dockerfile` を、一時的にコピーして (リポジトリは、変えない)、CA を、**証明書ごとに 1 ファイルに分けて**、信頼ストアに入れる行を足す (束を 1 ファイルのまま入れると、Java の信頼ストアには、最初の 1 枚しか入らず、Gradle が PKIX のエラーで落ちる)。`--network host` を付け、プロキシを、`ARG` で渡す (`ENV` には、焼き込まない)。実行 (`run`) のときは、`network_mode: host` と、プロキシの環境変数と、`JAVA_TOOL_OPTIONS` (Java のプロキシ) を足す。コンテナは、root で動く (`build/` などは、root 所有になる)。
- イメージに入った CA は、セッションが変わって、プロキシの CA が変わったときは、古くなる。TLS のエラーが出たら、`scripts/cloud-docker.sh build` で、作り直す。
- **Maven Central の `429 Too Many Requests`** (共有の出口の IP が、回数制限を受ける)。Google のミラー `maven-central.storage-download.googleapis.com` を、先に参照する。環境変数に `GRADLE_HOME_DIR=/var/cache/thermon-gradle` を設定し、セットアップスクリプトで、`/var/cache/thermon-gradle/init.d/maven-mirror.init.gradle.kts` を作る (`SETUP.md`)。**ファイルの中に、字下げの空白を入れない** (貼り付けで、全角スペースが混ざり、2 つの文が 1 行につながって、壊れたことがある)。
- **確認した範囲**: クラウドで、手動の同じ手順で、`build` が成功した (APK 4.6 MB。ミラー 472 件、`repo.maven.apache.org` 0 件)。`cloud-docker.sh` は、手元 (ローカル) で、CA が、システムと Java の信頼ストアに入ること、`run` が、プロキシの環境変数を渡して、ホストのネットワークで動くこと、イメージにプロキシが焼き込まれないことを、確認した。**`cloud-docker.sh` を、クラウドで実行することは、まだ確認していない。** `format`、`analyze`、`test`、`coverage`、`docs` も、クラウドでは、未実行。1 回目の `build` が、ktlint プラグインの解決で失敗し、再実行で成功したことがある (原因は、特定していない)。
- 同じ失敗を、繰り返さない。Docker を使わずに、VM の上で、直接ビルドする方法も、ある。

## プロジェクト構成

- `core/`: 純粋な Kotlin (Android に依存しない)。AWS のクライアント (`CognitoUnauthenticatedCredentials`、`DynamoDbReadings`)、測定値 (`Reading`)、表示用の文字列 (`formatReading`、`formatTemperature`、`formatChartTime`、`formatChartValue`)、グラフ用のデータ (`ChartRange`、`buildChartSeries`)、画面に表示する内容 (`loadSnapshot`)。**単体テストで、行と分岐のカバレッジ 100%。**
- `app/`: Android。画面と、設定の読み込み (`BuildConfig`) だけの、薄い層。ロジックは、`core` に置く (UI は、単体テストの対象外)。
- `gradle/libs.versions.toml`: ライブラリとプラグインの版 (版カタログ)。版を変えるときは、ここだけ。
- `config/detekt/detekt.yml`: detekt の規則 (既定に、上書きするものだけ)。
- `Dockerfile` (2 段: dev と emulator) / `docker-compose.yml` / `scripts/` (エミュレータの起動と待ち合わせ): 開発環境。`SETUP.md`: 環境構築と、検証の記録。

## AWS の設定と、秘密情報

- 接続先 (`thermon.region`、`thermon.identityPoolId`、`thermon.table`、`thermon.nodes`) は、`local.properties` (コミットしない) か、Gradle のプロパティ (`-Pthermon.table=...`) で渡す。リポジトリに、実際の ID プール ID や、MAC アドレスを書かない (文書の例は、ダミーの値にする)。
- **アクセスキーを、アプリや、リポジトリに入れない。** 必ず、Cognito の一時的な認証情報を使う。署名の鍵 (`*.jks`、`*.keystore`) も、コミットしない。
- 未認証ロールの権限は、そのテーブルの `dynamodb:Query` だけ。書き込み、削除、ほかのサービスの権限を、前提にしたコードを書かない。

## コーディング規約

ktlint (Kotlin 公式のスタイル) と、detekt (既定の規則) に従う。迷ったら、ツールが正しい。

- **インデントは、空白 4 つ。1 行は、100 桁まで** (`.editorconfig`)。整形は、`format-fix` が行う (手で、整形しない)。
- **ファイルの先頭に、著作権とライセンスを書く**:

  ```kotlin
  /*
   * Copyright (c) 2026 Tetsuya Higashi
   *
   * SPDX-License-Identifier: GPL-3.0-or-later
   */
  ```
- **コメントと KDoc は、日本語。** 句読点は、「, 」(カンマと空白) と「.」(ピリオド) にする (「、」と「。」は使わない)。ログのメッセージ、例外のメッセージ、識別子は、英語。
- **公開 (public) の宣言には、KDoc を付ける** (`docs` が、`Undocumented` の警告で失敗する)。`@param`、`@return`、`@throws` を、書く。`private` にも、意味と単位が、分かる説明を付ける。
- **KDoc の中で、`[s]` や `[%]` のように、角括弧で単位を書かない** (リンクとして解釈されて、`docs` が失敗する)。`(s)` や `(%)` と書く。リンクにしたいときだけ、`[クラス名]` と書く。
- **マジックナンバーを使わない** (detekt の `MagicNumber`)。時間、倍率、上限などは、`private const val` で、名前を付ける。
- **警告をゼロにする。** Kotlin の警告は、エラーになる (`allWarningsAsErrors`)。Android Lint も、警告をエラーにする。警告を抑える (`@Suppress`、`lint { disable }`) ときは、理由を、コメントに書く。
- **例外**: 汎用の例外 (`Exception`、`Throwable`) を、握りつぶさない。AWS SDK の失敗は、呼び出し元に、伝える。画面で、まとめて、扱うときは、`runCatching` で、メッセージを表示する。
- **AWS SDK のクライアントは、`core` の関数やクラスに、引数で渡す** (テストで、偽物に置き換えるため)。時刻など、外の状態を使うものも、引数 (関数) で渡す。
- **秘密情報や、環境固有の値を、コードに書かない** (上の「AWS の設定と、秘密情報」)。
- 変更しない値は、`val` にする (`var` は、必要なときだけ)。`!!` は、テスト以外で、使わない。

## 開発フロー

1. 機能開発は、`ccr-*` ブランチで行う。
2. Push 時に GitHub Actions (`.github/workflows/build.yml`) が、開発用の Docker イメージ (ローカルと同じ環境) で、`docker compose` のサービスを実行する (`SETUP.md` の「CI (GitHub Actions)」)。
   - `check` ジョブ: `build` / `test` / `coverage` / `format` / `analyze` を、サービスごとに実行する。失敗したら、ジョブが失敗する。実行のログを、アーティファクト (`<サービス名>_log`。`build_log` など) として保存する (失敗したときも保存する)。**APK は、アーティファクトに置かない。**
   - `docs` ジョブ: `docs` (Dokka の HTML。警告があれば失敗する)。生成したドキュメントを、アーティファクト `docs` と、ログ `docs_log` に保存する。
   - `deploy-docs` ジョブ: main への push のときだけ、ドキュメントを GitHub Pages に公開する (リポジトリの Settings > Pages > Source を「GitHub Actions」にしておく)。
   - `cleanup` ジョブ: 3 日より前の実行 (ログと artifact) を、push のたびに削除する (権限は `actions: write`)。artifact は、`retention-days: 3` で、3 日で期限が切れる。保存する期間を変えるときは、`build.yml` の `KEEP_DAYS` と、全ての `retention-days` を、そろえて変える。
   - Docker イメージは、レイヤーを GitHub Actions のキャッシュに保存する (`.github/actions/docker-image`)。Gradle の依存関係も、キャッシュに保存する (`.github/actions/gradle-cache`。`GRADLE_HOME_DIR`)。
   - アーティファクトの名前は、小文字のスネークケースにする。

### コード修正時のルール

- コードを修正したら、次の順に、全て成功することを確認する。**どれも、警告を出さない。** 警告やエラーが出たら修正して、出なくなるまで繰り返す。
  1. `docker compose run --rm format-fix` (整形。自動で直せない指摘があると、終了コード 1 になる)
  2. `docker compose run --rm format` (整形の確認)
  3. `docker compose run --rm analyze` (静的解析)
  4. `docker compose run --rm test` (単体テスト)
  5. `docker compose run --rm coverage` (`core` の行と分岐が 100%)
  6. `docker compose run --rm build` (APK のビルド)
  7. `docker compose run --rm docs` (ドキュメント。KDoc を変えたときは、必ず)
- ビルドや、テストを実行できなかったときは、そのことを報告する (成功したとは書かない)。
- **例外**: 変更が、`SETUP.md`、`README.md`、`.claude/CLAUDE.md` などの文書だけの場合は、ビルドしない。
- ビルドやコマンド、ツールの版を変えたら、`SETUP.md` も更新する (版は、実際に動かして、確認したものだけを書く)。
- ファイルを追加したら、Gradle の設定 (`build.gradle.kts`) と、必要なら、`SETUP.md` の構成も更新する。

### タスク完了時の動作

- 修正が完了し、全ての確認が成功したら、`git diff --stat` で差分を確認し、変更点と理由の簡潔なまとめを、ユーザーに報告する。
- ユーザーに依頼されない限り、`git commit` / `git push` は行わない。

## テスト

単体テストは、JVM 上で実行する (JUnit 5 と `kotlin-test`)。AWS SDK のクライアント (Kotlin のインターフェース) は、MockK で偽物にする。中断関数 (`suspend`) は、`kotlinx-coroutines-test` の `runTest` で、テストする。エミュレータや、実機での自動テスト (instrumented test) は、使わない。

- **配置**: `core/src/test/kotlin/` (テスト対象と、同じパッケージ)。クラス名は、`<対象>Test`。`app` には、テストを置かない (薄い層にする)。
- **カバレッジ**: `core` の行と分岐は、100% (JaCoCo の `jacocoTestCoverageVerification`)。ソースを足したり、関数を変えたりしたら、対応するテストを足す (正常系と、エラーの伝わり方)。到達しない分岐を作らない (到達しない `?: return` などを、書かない)。
- **テストの名前**: `<対象の関数名><条件>` を、キャメルケースで (例: `resolveFailsWithoutIdentityId`)。KDoc で、何を確認するかを書く。
- **時刻と乱数**: 外の状態は、引数で渡して、固定する (`CognitoUnauthenticatedCredentials` の `now` のように)。実際の時計を使う動作は、別に 1 つだけ、テストする。
- **実機と AWS での確認は、別に必要**: 実際の AWS への接続 (ID プール、テーブル、権限)、画面の表示、実機へのインストールは、単体テストでは、確認できない (文字列の表示は、実機で確認済み。グラフの表示は、エミュレータで確認した。実機では、まだ確認していない。画面を変えたら、また確認する)。

## 落とし穴 (検証で分かったこと。詳細は SETUP.md)

- `compileSdk` は 37 以上 (AWS SDK が使う OkHttp 5.5 の要求)。`buildToolsVersion` は `37.0.0` に固定する (コンテナの SDK は、読み取り専用)。足りない SDK のコンポーネントは、`Dockerfile` に足して、イメージを作り直す。
- `.gitignore` は、`*.jar` を無視する。Gradle ラッパーの `gradle-wrapper.jar` だけ、例外にしてある。
- AWS SDK の `getId { }` などの DSL は、拡張関数なので、`import aws.sdk.kotlin.services.cognitoidentity.getId` のように、インポートが要る。
- Cognito や DynamoDB の DSL (`QueryRequest { }` など) の中では、`inline` でない関数 (`joinToString` など) の引数の中から、中断関数を呼べない。`map` などの `inline` の関数を使う。
- 未使用の変数は、Kotlin のコンパイラの警告にならない。
- ktlint の `format-fix` は、1 回目に、自動で直せない指摘があると、終了コード 1 で終わる。直したあとに、`format` で、確認する。
- 画面 (`app`) を作るときは、システムバーの余白 (edge-to-edge。`targetSdk` 35 以上で必須) を、パディングに入れる (入れないと、文字が、アクションバーの下に、隠れる)。画面は、エミュレータで、確認する (`adb exec-out screencap -p` で、画像にして、見られる)。
- コンテナの `/dev/kvm` は、`kvm` グループ専用 (`KVM_GID`)。デバッグ用の署名の鍵は、ボリュームに残す (`ANDROID_USER_HOME`)。残さないと、`INSTALL_FAILED_UPDATE_INCOMPATIBLE` になる。
- エミュレータは、起動の直後に重く、`System UI isn't responding` が出ることがある (`scripts/emulator-wait.sh` で、落ち着くまで待つ)。
- ログの日本語が、`????` と化けるときは、コンテナの `LANG` (`Dockerfile` の `C.UTF-8`) を確認する。

## コミットガイドライン

- コミットメッセージは英語で記述
- **件名は、Conventional Commits の形式で書く**: `type(scope): description`。scope (括弧を含む) は、省略できる。description は、英語で、命令形 (例: `feat(core): Add the client of DynamoDB`)。
  - type は、次のどれか。
    - `feat`: 機能の追加
    - `fix`: バグの修正
    - `docs`: 文書とコメントだけの変更 (`*.md`、KDoc)
    - `style`: 整形、規約の適用 (動作を変えない)
    - `refactor`: 動作を変えない、書き換え
    - `test`: テストだけの変更
    - `build`: ビルドと、開発環境 (`build.gradle.kts`、`gradle/libs.versions.toml`、`Dockerfile`、`docker-compose.yml`、Gradle ラッパー) の変更
    - `ci`: CI (`.github/`) の変更
    - `chore`: そのほかの雑務 (最初のコミット、`.gitignore` など)
  - scope は、変更の場所: `core` / `app` (そのモジュールだけ)、`docker`、`gradle`。両方のモジュールにまたがるときは、省略する。
  - 互換性を壊す変更は、type (または scope) の直後に、感嘆符を付ける (Conventional Commits の仕様のとおり)。
  - 複数の type にまたがるときは、変更の中心になる type にする。
- 件名のあとに空行を 1 行入れて、本文を書く。本文はダッシュ/ハイフンで始まる箇条書き (例: `- Fix bug in parser`)
- コミットメッセージに `Co-Authored-By` または Claude 帰属行を含めない
- **rebase したあとの push は、`git push --force-with-lease --force-if-includes origin <ブランチ名>` で行う** (`--force` は使わない。リモートに、手元に取り込んでいないコミットがあれば、push は失敗する)。ユーザーに依頼されたときだけ、push する。
