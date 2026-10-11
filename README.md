# thermon

AWS に保存された温度・湿度のデータを読み取って、表示する Android アプリです (Kotlin)。

姉妹プロジェクト [esp32-thermo-system](https://github.com/sluchin/esp32-thermo-system) (ESP32 の温度センサのノードと、ゲートウェイ) が、AWS IoT Core 経由で、DynamoDB に保存したデータを、Cognito の ID プールの未認証ロールで、読み取ります (ログインは、ありません)。

```
ESP32 のノード ──BLE──> ゲートウェイ ──MQTT──> AWS IoT Core ──ルール──> DynamoDB
                                                                          ▲ Query (読み取りだけ)
Android アプリ (thermon) ──> Cognito ID プール (未認証) ──一時的な認証情報──┘
```

**Android Studio は使いません。** ビルド、テスト、整形、静的解析、ドキュメントの生成は、全て、コマンドラインで、Docker の中で実行します。

> **状態**: 雛形と、AWS のクライアント (Cognito の未認証の認証情報と、DynamoDB の Query) を、実装しました。グラフの表示 (温度と湿度。範囲は 1 h、6 h、24 h) も、実装しています。単体テストは、AWS SDK のクライアントを偽物にして、行います ([SETUP.md](SETUP.md))。

## 機能

- 機器 (MAC アドレス) ごとの、最新の温度・湿度と、受信した時刻を、1 行で表示する (電池残量は、`Reading` に入っているが、まだ表示しない)。
- 機器ごとに、温度と湿度の折れ線グラフを表示する。範囲は、画面の上のボタンで、1 時間、6 時間、24 時間から選ぶ (測定値を、区切りごとに平均する。測定値のない時間は、線を切る)。
- AWS への接続は、Cognito の未認証の ID の、一時的な認証情報だけを使う (アクセスキーは、使わない)。権限は、そのテーブルの `dynamodb:Query` だけ。
- DynamoDB から、機器と時刻の範囲を指定して、測定値を、古い順に取り出せる (`core` の `DynamoDbReadings.range()`。グラフの元になる)。

## プロジェクト構成

```
core/    純粋な Kotlin (Android に依存しない). AWS のクライアントと, 測定値の処理. 単体テストで, 行と分岐のカバレッジ 100%
app/     Android アプリ. 画面と, 設定の読み込みだけの, 薄い層
config/  detekt (静的解析) の設定
gradle/  版カタログ (libs.versions.toml) と, Gradle ラッパー
scripts/ エミュレータの起動, クラウドのセッションで Docker を使う補助, Maven のミラーの設定
.github/ GitHub Actions (CI と, ドキュメントの公開)
```

## 必要なもの

- Docker と Docker Compose V2 (`docker compose`)。JDK、Gradle、Android SDK は、Docker のイメージに入っているので、ホストには、入れません。
- 実機に入れるときだけ、ホストに adb。

## ビルド方法

### 1. 準備 (最初の 1 回)

コンテナを、ホストのユーザーで実行するため、`UID` と `GID` を、シェルの設定ファイルで、エクスポートします (生成物が、root 所有になりません)。bash の場合 (`~/.bashrc`):

```bash
export UID
export GID=$(id -g)
```

zsh の場合 (`~/.zshrc`) は、`export UID` と `export GID` です。新しいシェルを開いて、確認します。

```bash
docker compose run --rm dev id
```

### 2. イメージのビルド

JDK 17、Gradle 9.8.1、Android SDK (API 37) を含むイメージを、作ります (約 2 分、約 2.2 GB。ネットワークが必要です)。

```bash
docker compose build
```

### 3. アプリのビルド

```bash
docker compose run --rm build
```

デバッグ用の APK が、`app/build/outputs/apk/debug/app-debug.apk` に、できます。初回は、依存関係のダウンロードで、数分かかります (Gradle のキャッシュは、Docker のボリュームに残るので、2 回目からは、速くなります)。

## 設定 (AWS への接続)

接続先は、リポジトリに入れません。`local.properties` (コミットしない) を、リポジトリの直下に作って、書きます。

```properties
thermon.region=ap-northeast-1
thermon.identityPoolId=ap-northeast-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
thermon.table=thermo-readings
# 機器の MAC アドレス (コンマ区切り)
thermon.nodes=AA:BB:CC:DD:EE:01,AA:BB:CC:DD:EE:02
```

`thermon.identityPoolId` と `thermon.nodes` が空のままだと、アプリは、「AWS is not configured」と表示します。値を変えたら、ビルドし直します。AWS 側 (テーブル、IoT のルール、ID プール、未認証ロール) の準備は、[AWS_DYNAMODB.md](AWS_DYNAMODB.md) を、参照してください。詳しくは、[SETUP.md](SETUP.md) の「AWS への接続」です。

## 実機へのインストール

adb は、ホストで実行します (端末の設定を含む手順は、[SETUP.md](SETUP.md) の「実機へのインストール」)。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

インストールしたら、端末のアプリ一覧から **`thermon`** を開きます。AWS の準備のあとに、アプリを動かして確認する手順と、うまくいかないときの確認先は、[SETUP.md](SETUP.md) の「アプリを動かして確認する」です。

## エミュレータ

Android のエミュレータを、Docker の中で動かして、PC の上で、アプリを確認できます (KVM を使います。画面は、ブラウザで見ます)。

```bash
export KVM_GID=$(getent group kvm | cut -d: -f3)   # 最初の 1 回 (シェルの設定ファイルに書く)
docker compose build emulator                        # 約 9 分, 約 10.7 GB (最初の 1 回)
docker compose up -d emulator                        # 起動
docker compose exec emulator scripts/emulator-wait.sh
docker compose exec emulator adb install -r app/build/outputs/apk/debug/app-debug.apk
docker compose exec emulator adb shell am start -n jp.thermon.app/.MainActivity
```

画面は、<http://localhost:6080/vnc.html> で見ます。止めるときは、`docker compose down` です。詳しくは、[SETUP.md](SETUP.md) の「エミュレータ (Docker)」です。

## 開発

使えるコマンドは、次のとおりです (どれも `docker compose run --rm <サービス>`)。

| サービス | 内容 |
|:---|:---|
| `build` | デバッグ用の APK をビルドする。Kotlin の警告が出たら失敗する |
| `test` | 単体テストを実行する |
| `coverage` | `core` の行と分岐のカバレッジが 100% でなければ失敗する |
| `format` | 整形が必要なら失敗する (確認だけ。ktlint) |
| `format-fix` | 整形して、ファイルを書き換える |
| `analyze` | 静的解析 (detekt と Android Lint)。指摘があれば失敗する |
| `docs` | ドキュメント (Dokka の HTML) を生成する (`core/build/dokka/html/index.html`)。KDoc のない公開の宣言があれば失敗する |
| `clean` | 生成物 (`build/` など) を消す (Gradle のキャッシュは、消さない) |
| `emulator` | Android エミュレータを、KVM で動かす (`docker compose up -d emulator`) |
| `dev` | 開発シェル (中で `./gradlew` を、直接実行できる) |

コードを修正したら、`format-fix`、`format`、`analyze`、`test`、`coverage`、`build`、`docs` の順に、全て成功することを確認します。コーディング規約、テストの方針、コミットの書き方は、[.claude/CLAUDE.md](.claude/CLAUDE.md) にあります。

## CI

Push 時に、GitHub Actions が、開発用の Docker イメージで、`build` `test` `coverage` `format` `analyze` `docs` を実行します。ログは、アーティファクトに保存します (APK は、置きません)。main への push では、ドキュメント (Dokka) を、GitHub Pages に公開します。詳しくは、[SETUP.md](SETUP.md) の「CI (GitHub Actions)」です。

## ドキュメント

- [AWS_CLI.md](AWS_CLI.md): AWS CLI の準備 (インストール、`aws login`、IAM ユーザーとアクセスキー、CloudShell)。
- [AWS_DYNAMODB.md](AWS_DYNAMODB.md): AWS 側の準備 (DynamoDB のテーブル、IoT のルール、Cognito の ID プール、未認証ロール)。
- [SETUP.md](SETUP.md): 開発環境の構築と、検証の記録 (ツールの版、落とし穴、AWS への接続の詳細)。
- [.claude/CLAUDE.md](.claude/CLAUDE.md): コマンド、コーディング規約、テストの方針、コミットガイドライン。

## ライセンス

GPL-3.0-or-later ([LICENSE](LICENSE))。
