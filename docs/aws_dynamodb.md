# AWS に届いたデータを DynamoDB に保存して、アプリから読む

AWS IoT Core に届いた温度・湿度・気圧・電池残量を DynamoDB に 1 件ずつ保存して、Android アプリ (thermon) から読む手順です。アプリは Cognito の「未認証ロール」で、読み取りだけを許可されます (ログインはありません)。

> **注意**: この手順は AWS のドキュメントに基づいて書いたものです。AWS の画面、料金、サービスの提供状況は変わることがあるので、最新の情報を確認してください。

AWS CLI をまだ使えるようにしていないときは先に [AWS_CLI.md](aws_cli.md) の手順を行います (CLI のインストール、認証情報の設定、アカウント ID の確認)。

## 1. 構成

```text
ゲートウェイ ──MQTT──> AWS IoT Core ──ルール──> DynamoDB (テーブル thermo-readings)
                          thermo/#                       ▲
                                                         │ Query (読み取りだけ)
thermon ──> Cognito ID プール (未認証) ──一時的な認証情報──┘
```

- **ルール**: 届いた MQTT のメッセージを SQL で絞り込んで、別のサービスに渡す、IoT Core の機能です。DynamoDBv2 アクションはメッセージの項目をそのまま、テーブルの属性として書きます。
- **Cognito ID プール**: アプリに AWS の一時的な認証情報を渡す仕組みです。「未認証ロール」を有効にすると、ログインなしで、決めた権限だけの認証情報を受け取れます。
- ルールの設定だけで保存できます。コードを書くのはアプリだけです。

### 1.1 届くメッセージ

ゲートウェイはセンサの値をトピック `thermo/...` (SwitchBot と Thermo ノードの両方) に JSON で送ります。ルールはこの項目を使います。

| 項目 | 内容 |
|:---|:---|
| `node` | 機器の MAC アドレス (例: `ED:2E:C4:46:3B:11`) |
| `type` | `switchbot` または `thermo` |
| `temperature_c` | 温度 [℃] |
| `humidity` | 湿度 [%] (測れないセンサでは、ない) |
| `pressure_hpa` | 気圧 [hPa] (Thermo ノードに気圧センサ (BMP280) があるときだけ。ないときはない) |
| `battery` | 電池残量 [%] (SwitchBot だけ。わからないときはない) |
| `uptime_ms` | ゲートウェイの稼働時間 [ms] |
| `timestamp` | ゲートウェイの時計の時刻 (時計が合っていないと、ない) |

### 1.2 DynamoDB と Timestream

| 項目 | DynamoDB | Timestream |
|:---|:---|:---|
| 時系列の保存 | キーの設計で対応する (機器と時刻) | 時系列専用 |
| 設定の手間 | 少ない | 多い |
| 提供 | 常に利用できる | Timestream for LiveAnalytics は 2025 年 6 月から、新しい利用者を受け付けていない (私の知る限り。最新の状況は AWS で確認してください)。InfluxDB 版は別のサービス |
| 向くこと | 機器と期間を指定して取り出す。個人の規模 | 複雑な集計、SQL での分析 |

このドキュメントは**DynamoDB** で書きます。個人の規模 (1 台、10 秒に 1 件) なら、十分です。

### 1.3 制限

- **取り出し方**: 機器 (`node`) を指定して、時刻の範囲で取り出します。「全ての機器の、ある時刻のデータ」は 1 回では取り出せません (機器ごとに、取り出します)。
- **集計**: 平均や最大値はアプリ側で計算します (DynamoDB は集計しません)。
- **件数と料金**: 10 秒に 1 件だと、1 台で 1 日に 8,640 件、1 か月に約 26 万件です。書き込みはオンデマンドの課金です。保存が増え続けないように、古い項目を自動で消します (TTL。2.1)。料金はリージョンごとの料金表で確認してください。

## 2. テーブルを作る

### 2.1 項目の設計

| 属性 | 型 | 内容 |
|:---|:---|:---|
| `node` | 文字列 | パーティションキー。機器の MAC アドレス (例: `ED:2E:C4:46:3B:11`)。メッセージの `node` |
| `ts` | 数値 | ソートキー。IoT Core が受信した時刻 (UNIX 時刻 [s])。ルールが付ける |
| `type` | 文字列 | `switchbot` または `thermo` |
| `temperature_c` | 数値 | 温度 [℃] |
| `humidity` | 数値 | 湿度 [%] (測れないセンサでは、ない) |
| `pressure_hpa` | 数値 | 気圧 [hPa] (気圧センサのある Thermo ノードだけ。ないときはない) |
| `battery` | 数値 | 電池残量 [%] (SwitchBot だけ。わからないときはない) |
| `expires_at` | 数値 | この時刻 (UNIX 時刻 [s]) を過ぎたら、自動で消える (TTL。受信から 30 日) |

- ソートキーにメッセージの `timestamp` (ゲートウェイの時計) を使わず、ルールの `timestamp()` (IoT Core の時計) を使います。ゲートウェイの時計が合っていないと、`timestamp` が入らないことがあるためです。
- `ts` は秒単位です。同じ機器から、同じ秒に 2 件届くと、上書きされます。ゲートウェイは 1 台あたり 10 秒に 1 回なので、通常は起きません。

### 2.2 作成する

AWS CLI の例です (リージョンは `ap-northeast-1`。アカウント ID は自分のものに置き換えます)。

```bash
aws dynamodb create-table \
  --table-name "$TABLE" \
  --attribute-definitions AttributeName=node,AttributeType=S AttributeName=ts,AttributeType=N \
  --key-schema AttributeName=node,KeyType=HASH AttributeName=ts,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

# 古い項目を自動で消す (属性 expires_at)
aws dynamodb update-time-to-live \
  --table-name "$TABLE" \
  --time-to-live-specification Enabled=true,AttributeName=expires_at
```

コンソールで作る場合は「DynamoDB → テーブル → テーブルの作成」で、テーブル名 `thermo-readings`、パーティションキー `node` (文字列)、ソートキー `ts` (数値)、設定は「オンデマンド」にします。作成後に「追加の設定」の「有効期限 (TTL)」を開いて、属性名に `expires_at` を指定します。

## 3. IoT Core が DynamoDB に書き込むための権限 (IAM ロール)

IoT Core がテーブルに書き込むためのロールを作ります。ロールの種類は「カスタム信頼ポリシー」にします (「AWS のサービス」の「IoT」を選ぶと、余計な管理ポリシーが付きます)。

### 3.0 共通の変数

以降のコマンドは同じシェルで、続けて実行します (シェルを閉じたら、次の変数を設定し直します)。アカウント ID (12 桁の数字) はコマンドで取り出して、変数に入れるので、ファイルや文書に書き写しません。

```bash
export AWS_REGION=ap-northeast-1
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
TABLE=thermo-readings
echo "$ACCOUNT_ID"
```

- 出力が出ないときは [AWS_CLI.md](aws_cli.md) の設定が終わっていません。
- 以降で作る JSON ファイル (`iot-*.json`、`rule-*.json`、`unauth-*.json`) にはアカウント ID などが入ります。リポジトリの外 (`~/tmp` など) で実行すると、ファイルが残りません。リポジトリの中で実行したときは `.gitignore` が無視します。**コミットしません。**
- アカウント ID はパスワードではありませんが、公開する必要もありません。リポジトリには書かないでください。

### 3.1 ロールを作る

```bash
cat > iot-trust.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Service": "iot.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }
  ]
}
JSON

cat > iot-put.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "dynamodb:PutItem",
      "Resource": "arn:aws:dynamodb:ap-northeast-1:${ACCOUNT_ID}:table/${TABLE}"
    }
  ]
}
JSON

aws iam create-role --role-name thermo-iot-dynamodb \
  --assume-role-policy-document file://iot-trust.json
aws iam put-role-policy --role-name thermo-iot-dynamodb \
  --policy-name put-item --policy-document file://iot-put.json
```

ロールの ARN (`arn:aws:iam::アカウント ID:role/thermo-iot-dynamodb`) は次の章のルールで、`${ACCOUNT_ID}` から作ります。

## 4. ルールを作る

### 4.1 SQL の意味

この SQL は単独では実行しません。4.2 の `rule-dynamodb.json` の `"sql"` に 1 行で入れてあります (4.2 のコマンドで、ルールと一緒に登録されます)。ここでは、その意味だけを説明します。

```sql
SELECT *, floor(timestamp() / 1000) AS ts, floor(timestamp() / 1000) + 2592000 AS expires_at
FROM 'thermo/#'
WHERE isUndefined(temperature_c) = false
```

- `FROM 'thermo/#'` は `thermo/` で始まる全てのトピックです (SwitchBot と、Thermo ノードの両方)。
- `WHERE` で `temperature_c` のないメッセージを除きます。
- `timestamp()` は IoT Core が受信した時刻 [ミリ秒] です。`floor(... / 1000)` で秒にします。`2592000` は 30 日の秒数です。
- `SELECT *` でメッセージの全ての項目 (`node`、`type`、`temperature_c`、`humidity`、`pressure_hpa`、`battery`、`uptime_ms`、`timestamp`) をそのまま渡します。項目が増えても (気圧の `pressure_hpa` など)、ルールとテーブルは変えずに済みます。

### 4.2 ルールを作る

```bash
cat > rule-dynamodb.json <<JSON
{
  "sql": "SELECT *, floor(timestamp() / 1000) AS ts, floor(timestamp() / 1000) + 2592000 AS expires_at FROM 'thermo/#' WHERE isUndefined(temperature_c) = false",
  "awsIotSqlVersion": "2016-03-23",
  "ruleDisabled": false,
  "actions": [
    {
      "dynamoDBv2": {
        "roleArn": "arn:aws:iam::${ACCOUNT_ID}:role/thermo-iot-dynamodb",
        "putItem": { "tableName": "${TABLE}" }
      }
    }
  ]
}
JSON


aws iot create-topic-rule \
  --rule-name thermo_to_dynamodb --topic-rule-payload file://rule-dynamodb.json

# 登録されたルールを確認する
aws iot get-topic-rule --rule-name thermo_to_dynamodb
```

ルール名は英数字とアンダースコアだけです。

コンソールで作る場合は「IoT Core → メッセージのルーティング → ルール → ルールを作成」で、SQL に 4.1 を貼って、アクションは「DynamoDB (DynamoDBv2)」を選びます (「DynamoDB」ではなく、メッセージの項目を属性に分ける「DynamoDBv2」)。テーブルは `thermo-readings`、IAM ロールは `thermo-iot-dynamodb` です。

## 5. データが届いているか確認する

ゲートウェイを動かして、少し待ってから、確認します。

```bash
# 件数と、最新の数件 (Scan は、少量のテーブルで、確認に使うだけにしてください)
aws dynamodb scan --table-name "$TABLE" --max-items 5

# 機器を指定して、最新の 3 件を取り出す
aws dynamodb query --table-name "$TABLE" \
  --key-condition-expression "#n = :n" \
  --expression-attribute-names '{"#n":"node"}' \
  --expression-attribute-values '{":n":{"S":"<機器の MAC アドレス>"}}' \
  --no-scan-index-forward --limit 3
```

何も入らないときはルールの「エラーアクション」と、CloudWatch Logs (IoT のログを有効にしたとき) を見ます。よくある原因はロールの権限不足 (`dynamodb:PutItem`)、テーブル名やリージョンの違い、キーの `node` か `ts` が SQL の結果にない場合です。

## 6. アプリ用の認証 (Cognito ID プール、未認証ロール)

### 6.1 ID プールを作る

```bash
IDENTITY_POOL_ID=$(aws cognito-identity create-identity-pool \
  --identity-pool-name thermo_client \
  --allow-unauthenticated-identities \
  --query IdentityPoolId --output text)
echo "$IDENTITY_POOL_ID"
```

出力の `IdentityPoolId` (`ap-northeast-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) は変数 `IDENTITY_POOL_ID` に入っています。アプリに書く値です (`local.properties` に書きます。リポジトリには書きません)。

### 6.2 未認証ロール (読み取りだけ)

```bash
cat > unauth-trust.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Federated": "cognito-identity.amazonaws.com" },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": { "cognito-identity.amazonaws.com:aud": "${IDENTITY_POOL_ID}" },
        "ForAnyValue:StringLike": { "cognito-identity.amazonaws.com:amr": "unauthenticated" }
      }
    }
  ]
}
JSON

cat > unauth-read.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "dynamodb:Query",
      "Resource": "arn:aws:dynamodb:ap-northeast-1:${ACCOUNT_ID}:table/${TABLE}"
    }
  ]
}
JSON


aws iam create-role --role-name thermo-client-unauth \
  --assume-role-policy-document file://unauth-trust.json
aws iam put-role-policy --role-name thermo-client-unauth \
  --policy-name read-thermo --policy-document file://unauth-read.json

# ID プールに、未認証ロールとして登録する
aws cognito-identity set-identity-pool-roles \
  --identity-pool-id "${IDENTITY_POOL_ID}" \
  --roles "unauthenticated=arn:aws:iam::${ACCOUNT_ID}:role/thermo-client-unauth"

# 登録されたロールを確認する
aws cognito-identity get-identity-pool-roles \
  --identity-pool-id "${IDENTITY_POOL_ID}"
```

`IDENTITY_POOL_ID` は 6.1 の値、`ACCOUNT_ID` は 3.0 の値です。シェルが変わったときは設定し直します。

### 6.3 安全のために

未認証ロールは**アプリの中の ID プール ID を知っている人なら、誰でも**、この権限を使えます (ID はアプリのファイルから取り出せます)。次の点を守ってください。

- 権限は `dynamodb:Query` だけをこのテーブルだけに付けます (上のポリシーのとおり)。書き込み、削除、ほかのサービスは付けません。
- アクセスキーをアプリに埋め込みません。必ずCognito の一時的な認証情報を使います。
- アプリをほかの人に配るときは Cognito のユーザープールによるログインに替えます (今は自分だけなので、未認証ロールで進めます)。

## 7. thermon から読む

thermon の `core` がこの手順で作った ID プールとテーブルを使います。接続先はリポジトリに入れず、`local.properties` に書きます ([README.md](../README.md) の「設定」)。

```properties
thermon.region=ap-northeast-1
thermon.identityPoolId=<6.1 の IdentityPoolId>
thermon.table=thermo-readings
thermon.nodes=<機器の MAC アドレス (コンマ区切り)>
```

- `CognitoUnauthenticatedCredentials` が 6.1 の ID プールから、未認証の ID と、一時的な認証情報を受け取ります (期限の 5 分前まで、使い回します)。
- `DynamoDbReadings` が `node` と `ts` の範囲で `Query` します (結果が 1 MB を超えるときは `lastEvaluatedKey` で続きを取り出します)。
- `ts` と `node` は DynamoDB の予約語と重なるおそれがあるので、`expressionAttributeNames` で別名にしています。
- `Query` は 1 回に最大 1 MB までです。10 秒に 1 件の 1 日分 (8,640 件) は 1 回で入らないことがあります。

### 7.1 気圧を表示する

Thermo ノードに気圧センサを付けると (esp32-thermo-system の `SETUP.md` の「Thermo ノードのセンサ」)、項目に `pressure_hpa` が増えます。**AWS 側の設定は変えません** (ルールは `SELECT *`、テーブルはキーのほかの属性を決めない、未認証ロールの権限は `dynamodb:Query` だけで属性によらない)。

- thermon は `pressure_hpa` を読んで、最新の値の 1 行 (`1013.2 hPa`) と、気圧のグラフ (`Pressure`) に出します。気圧のない機器 (SwitchBot、DHT11 のノード) は、気圧のグラフに線が出ません。
- 端末に保存した測定値 (SQLite) にも、気圧の列を足しました (データベースの版 2)。アプリを更新すると、保存してあった測定値は消さずに、列だけを足します (それまでの測定値の気圧は、なし)。
- 気圧が届いているかは、esp32-thermo-system の `AWS_SETUP.md` の 8.1 のコマンドで確かめます。

## 8. これから

- 古いデータは `expires_at` で受信から 30 日後に消えます。長く残したいときは TTL の期間 (4.1 の `2592000`) を長くするか、S3 にエクスポートします。
- 複数人で使うときは Cognito のユーザープールによるログインに替えます。
