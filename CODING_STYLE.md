# コーディングスタイル

Kotlin のコードは、Kotlin 公式のコーディング規約に従います。下に、その日本語訳を載せます。整形は ktlint (`format-fix`)、規則の確認は detekt と Android Lint (`analyze`) が行います。迷ったら、ツールが正しいです。

## このプロジェクトでの違い

公式の規約に加えて、このプロジェクトでは、次のように決めています (詳しくは `.claude/CLAUDE.md` の「コーディング規約」)。公式の規約と違うところは、こちらを優先します。

- **KDoc の `@param` と `@return`**: 公式の規約は「なるべく使わない」ですが、このプロジェクトでは、公開の関数に、全ての `@param`、`@return`、`@throws` を書きます (`docs` が、説明のないものを警告にするため)。
- **コメントと KDoc は日本語**: 句読点は「, 」(カンマと空白) と「.」(ピリオド) にします。ログのメッセージ、例外のメッセージ、識別子は英語です。
- **ファイルの先頭に、著作権とライセンス** (`Copyright (c) 2026 Tetsuya Higashi`、`SPDX-License-Identifier: GPL-3.0-or-later`) を書きます。
- **1 行は 100 桁まで**、インデントは空白 4 つ (`.editorconfig`)。
- **マジックナンバーを使わない** (detekt の `MagicNumber`)。`private const val` で名前を付けます。

## Kotlin のコーディング規約 (公式の日本語訳)

> この節は、Kotlin 公式のドキュメント「[Coding conventions](https://kotlinlang.org/docs/coding-conventions.html)」の日本語訳です。
>
> - 原文: [JetBrains/kotlin-web-site](https://github.com/JetBrains/kotlin-web-site) の `docs/topics/coding-conventions.md` (コミット `f51dacd`、2026-08-12)。
> - 原文のライセンス: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)。この訳も、Apache License 2.0 に従います。
> - 変更点: 日本語に翻訳しました。見出しの深さを 1 段下げ、ドキュメントのサイトだけで使う記法 (`{style="note"}` など) を外し、サイトの中のリンクを `https://kotlinlang.org/docs/` の URL にしました。コードの例は、原文のままです。

よく知られていて、従いやすいコーディング規約は、どのプログラミング言語にも欠かせません。ここでは、Kotlin を使うプロジェクトのための、コードのスタイルと、コードの構成についての指針を示します。

### IDE でスタイルを設定する

Kotlin の 2 つの代表的な IDE、[IntelliJ IDEA](https://www.jetbrains.com/idea/) と [Android Studio](https://developer.android.com/studio/) は、コードのスタイルを強力に支援します。この規約に合わせて、コードを自動で整形するように設定できます。

#### スタイルガイドを適用する

1. **Settings/Preferences | Editor | Code Style | Kotlin** を開きます。
2. **Set from...** を押します。
3. **Kotlin style guide** を選びます。

#### コードがスタイルガイドに従っているかを確かめる

1. **Settings/Preferences | Editor | Inspections | General** を開きます。
2. **Incorrect formatting** の検査を有効にします。
スタイルガイドに書かれた、ほかの問題 (名前の付け方など) を確かめる検査は、最初から有効です。

詳しくは、[Migrate to Kotlin code style with IntelliJ IDEA](https://kotlinlang.org/docs/code-style-migration-guide.html) を見てください。

### ソースコードの構成

#### ディレクトリの構成

Kotlin だけのプロジェクトでは、共通のルートのパッケージを省いて、パッケージの構成に合わせたディレクトリの構成にすることを勧めます。たとえば、プロジェクトのコードが、全て `org.example.kotlin` のパッケージと、その下のパッケージにあるなら、`org.example.kotlin` のパッケージのファイルは、ソースのルートの直下に置き、`org.example.kotlin.network.socket` のファイルは、ソースのルートの `network/socket` のサブディレクトリに置きます。

> JVM の場合: Kotlin を Java と一緒に使うプロジェクトでは、Kotlin のソースファイルは、Java のソースファイルと同じソースのルートに置き、同じディレクトリの構成に従います。各ファイルは、それぞれの `package` 文に対応するディレクトリに置きます。

#### ソースファイルの名前

Kotlin のファイルに、クラスかインターフェイスが 1 つだけある (関係するトップレベルの宣言は、あってもよい) ときは、ファイルの名前を、クラスの名前に `.kt` の拡張子を付けたものにします。これは、全ての種類のクラスとインターフェイスに当てはまります。ファイルに、複数のクラスがあるか、トップレベルの宣言だけがあるときは、ファイルの中身を表す名前を選んで、その名前を付けます。各単語の最初の文字を大文字にする [アッパーキャメルケース](https://en.wikipedia.org/wiki/Camel_case) を使います。たとえば、`ProcessDeclarations.kt` です。

ファイルの名前は、ファイルの中のコードが何をするかを表すようにします。そのため、ファイルの名前に、`Util` のような意味のない単語を使うのは避けます。

##### マルチプラットフォームのプロジェクト

マルチプラットフォームのプロジェクトでは、プラットフォームごとのソースセットにある、トップレベルの宣言を持つファイルに、ソースセットの名前に対応する接尾辞を付けます。たとえば、次のようにします。

* **jvm**Main/kotlin/Platform.**jvm**.kt
* **android**Main/kotlin/Platform.**android**.kt
* **ios**Main/kotlin/Platform.**ios**.kt

共通のソースセットでは、トップレベルの宣言を持つファイルに、接尾辞を付けません。たとえば、`commonMain/kotlin/Platform.kt` です。

###### 技術的な詳細

マルチプラットフォームのプロジェクトで、このファイルの名前の付け方を勧めるのは、JVM の制約のためです。JVM では、トップレベルのメンバー (関数、プロパティ) を持てません。

これを回避するため、Kotlin の JVM のコンパイラは、トップレベルのメンバーの宣言を入れる、包み込むクラス (いわゆる「ファイルファサード」) を作ります。ファイルファサードは、ファイルの名前から作った、内部の名前を持ちます。

そして、JVM では、完全修飾名 (FQN) が同じクラスを、複数持てません。そのため、Kotlin のプロジェクトを、JVM 向けにコンパイルできないことがあります。

```none
root
|- commonMain/kotlin/myPackage/Platform.kt // contains 'fun count() { }'
|- jvmMain/kotlin/myPackage/Platform.kt // contains 'fun multiply() { }'
```

ここでは、2 つの `Platform.kt` のファイルが同じパッケージにあるので、Kotlin の JVM のコンパイラは、ファイルファサードを 2 つ作り、どちらも FQN が `myPackage.PlatformKt` になります。これは、「Duplicate JVM classes」のエラーになります。

避ける最も簡単な方法は、上の指針に従って、一方のファイルの名前を変えることです。この名前の付け方は、コードの読みやすさを保ちながら、衝突を避けるのに役立ちます。

> この推奨が、不要に思える場合が 2 つありますが、それでも従うことを勧めます。
>
> * JVM 以外のプラットフォームでは、ファイルファサードが重なっても、問題になりません。それでも、この名前の付け方は、ファイルの名前を一貫させるのに役立ちます。
> * JVM で、ソースファイルにトップレベルの宣言がなければ、ファイルファサードは作られず、名前の衝突も起きません。
>
>   それでも、この名前の付け方は、ちょっとしたリファクタリングや、追加で、トップレベルの関数が入り、同じ「Duplicate JVM classes」のエラーになるのを避けるのに役立ちます。

#### ソースファイルの構成

複数の宣言 (クラス、トップレベルの関数やプロパティ) を、同じ Kotlin のソースファイルに置くことは、それらの宣言が、意味の上で互いに密接に関係していて、ファイルの大きさが妥当 (数百行を超えない) な限り、勧められます。

特に、クラスの全ての利用者に関係する、クラスの拡張関数を定義するときは、クラスと同じファイルに置きます。特定の利用者だけに意味のある拡張関数を定義するときは、その利用者のコードの隣に置きます。あるクラスの全ての拡張を入れるためだけの、ファイルを作るのは避けます。

#### クラスの配置

クラスの中身は、次の順に置きます。

1. プロパティの宣言と、初期化のブロック
2. 二次コンストラクタ
3. メソッドの宣言
4. コンパニオンオブジェクト

メソッドの宣言を、アルファベット順や、可視性の順に並べたり、通常のメソッドと、拡張のメソッドを分けたりしません。代わりに、関係するものをまとめて置き、クラスを上から下へ読む人が、何が起きているかの流れを追えるようにします。順番 (上位のものを先にするか、その逆か) を決めて、それを守ります。

ネストしたクラスは、それを使うコードの隣に置きます。クラスが、外から使われることを意図していて、クラスの中から参照されないなら、最後 (コンパニオンオブジェクトの後ろ) に置きます。

#### インターフェイスの実装の配置

インターフェイスを実装するときは、実装するメンバーを、インターフェイスのメンバーと同じ順に置きます (必要なら、実装のための、追加の private のメソッドを、間に挟みます)。

#### オーバーロードの配置

クラスの中では、オーバーロードを、常に隣り合わせに置きます。

### 名前の付け方

Kotlin のパッケージと、クラスの名前の付け方は、とても単純です。

* パッケージの名前は、常に小文字で、アンダースコアを使いません (`org.example.project`)。複数の単語の名前は、一般に勧めませんが、どうしても複数の単語が要るときは、単につなげるか、キャメルケースにします (`org.example.myProject`)。

* クラスとオブジェクトの名前は、アッパーキャメルケースにします。

```kotlin
open class DeclarationProcessor { /*...*/ }

object EmptyDeclarationProcessor : DeclarationProcessor() { /*...*/ }
```

#### 関数の名前

関数、プロパティ、ローカル変数の名前は、小文字で始め、アンダースコアのないキャメルケースにします。

```kotlin
fun processDeclarations() { /*...*/ }
var declarationCount = 1
```

#### クラスのような関数の名前

関数の名前を、代わりに、クラスの名前の付け方に従わせる、例外が 2 つあります。この種の関数は、たいてい、トップレベルで定義します。

* クラスのインスタンスを作るファクトリ関数は、抽象的な戻り値の型と、同じ名前にしてよいです。

   ```kotlin
   interface Foo { /*...*/ }

   class FooImpl : Foo { /*...*/ }

   fun Foo(): Foo { return FooImpl() }
   ```

* `Unit` を返す `@Composable` の関数。

   ```kotlin
   @Composable fun TabHeader { /*...*/ }
   ```

#### テストのメソッドの名前

テストでは (そして、テストで**だけ**は)、バッククォートで囲んだ、空白を含むメソッドの名前を使えます。このようなメソッドの名前は、Android のランタイムでは、API レベル 30 からしか使えないことに注意してください。テストのコードでは、メソッドの名前に、アンダースコアも使えます。

```kotlin
class MyTestCase {
    @Test fun `ensure everything works`() { /*...*/ }

    @Test fun ensureEverythingWorks_onAndroid() { /*...*/ }
}
```

#### プロパティの名前

定数 (`const` を付けたプロパティか、独自の `get` 関数がなく、深く不変なデータを持つ、トップレベルかオブジェクトの `val` のプロパティ) の名前は、[スクリーミングスネークケース](https://en.wikipedia.org/wiki/Snake_case) に従って、全て大文字で、アンダースコアで区切ります。

```kotlin
const val MAX_COUNT = 8
val USER_NAME_FIELD = "UserName"
```

振る舞いのあるオブジェクトや、変更できるデータを持つ、トップレベルかオブジェクトのプロパティの名前は、キャメルケースにします。

```kotlin
val mutableCollection: MutableSet<String> = HashSet()
```

シングルトンのオブジェクトへの参照を持つプロパティの名前は、`object` の宣言と同じ名前の付け方にしてよいです。

```kotlin
val PersonComparator: Comparator<Person> = /*...*/
```

列挙の定数は、使い方に応じて、全て大文字でアンダースコアで区切る名前 ([スクリーミングスネークケース](https://en.wikipedia.org/wiki/Snake_case)。`enum class Color { RED, GREEN }`) か、アッパーキャメルケースの名前の、どちらにしてもかまいません。

#### バッキングプロパティの名前

クラスに、概念の上では同じだが、一方は公開の API の一部で、もう一方は実装の詳細である、2 つのプロパティがあるときは、private のプロパティの名前の先頭に、アンダースコアを付けます。

```kotlin
class C {
    private val _elementList = mutableListOf<Element>()

    val elementList: List<Element>
        get() = _elementList
}
```

#### よい名前を選ぶ

クラスの名前は、たいてい、クラスが _何であるか_ を表す、名詞か名詞句です。`List`、`PersonReader`。

メソッドの名前は、たいてい、メソッドが _何をするか_ を言う、動詞か動詞句です。`close`、`readPersons`。名前は、メソッドが、オブジェクトを変更するのか、新しいオブジェクトを返すのかも、示すようにします。たとえば、`sort` は、コレクションを、その場で並べ替え、`sorted` は、コレクションの並べ替えた複製を返します。

名前は、それが何のためのものかを、はっきりさせるようにします。そのため、名前に、意味のない単語 (`Manager`、`Wrapper`) を使うのは避けるのが最善です。

宣言の名前の一部に、頭字語を使うときは、次の規則に従います。

* 2 文字の頭字語は、両方の文字を大文字にします。たとえば、`IOStream` です。
* 2 文字より長い頭字語は、最初の文字だけを大文字にします。たとえば、`XmlFormatter` や `HttpInputStream` です。

### 整形

#### インデント

インデントには、空白 4 つを使います。タブは使いません。

波括弧は、開き括弧を、構文の始まる行の末尾に置き、閉じ括弧を、別の行に、開いた構文と横の位置をそろえて置きます。

```kotlin
if (elements != null) {
    for (element in elements) {
        // ...
    }
}
```

> Kotlin では、セミコロンは省略できるので、改行に意味があります。言語の設計は、Java のスタイルの波括弧を前提にしているので、別の整形のスタイルを使おうとすると、思わぬ振る舞いに出会うことがあります。

#### 横の空白

* 二項演算子の前後に、空白を入れます (`a + b`)。例外: 範囲の演算子の前後には、空白を入れません (`0..i`)。
* 単項演算子の前後には、空白を入れません (`a++`)。
* 制御フローのキーワード (`if`、`when`、`for`、`while`) と、対応する開き丸括弧の間に、空白を入れます。
* 主コンストラクタの宣言、メソッドの宣言、メソッドの呼び出しでは、開き丸括弧の前に、空白を入れません。

```kotlin
class A(val x: Int)

fun foo(x: Int) { ... }

fun bar() {
    foo(1)
}
```

* `(`、`[` の後ろと、`]`、`)` の前には、決して空白を入れません。
* `.` と `?.` の前後には、決して空白を入れません。`foo.bar().filter { it > 2 }.joinToString()`、`foo?.bar()`。
* `//` の後ろに、空白を入れます。`// This is a comment`。
* 型パラメータを指定する山括弧の前後には、空白を入れません。`class Map<K, V> { ... }`。
* `::` の前後には、空白を入れません。`Foo::class`、`String::length`。
* null を許す型を示す `?` の前には、空白を入れません。`String?`。

一般的な規則として、どんな種類の横のそろえも、避けます。識別子を、長さの違う名前に変えても、宣言や、どの使用箇所の整形にも、影響しないようにします。

#### コロン

次の場合は、`:` の前に、空白を入れます。

* 型と、上位の型を区切るとき。
* 上位のクラスのコンストラクタや、同じクラスの別のコンストラクタに、委譲するとき。
* `object` のキーワードの後ろ。

宣言と、その型を区切るときは、`:` の前に、空白を入れません。

`:` の後ろには、常に空白を入れます。

```kotlin
abstract class Foo<out T : Any> : IFoo {
    abstract fun foo(a: Int): T
}

class FooImpl : Foo() {
    constructor(x: String) : this(x) { /*...*/ }

    val x = object : IFoo { /*...*/ }
}
```

#### クラスのヘッダー

主コンストラクタのパラメータが少ないクラスは、1 行で書けます。

```kotlin
class Person(id: Int, name: String)
```

ヘッダーが長いクラスは、主コンストラクタの各パラメータが、インデントした別の行になるように、整形します。また、閉じ丸括弧は、新しい行に置きます。継承を使うときは、上位のクラスのコンストラクタの呼び出しか、実装するインターフェイスの一覧を、閉じ丸括弧と同じ行に置きます。

```kotlin
class Person(
    id: Int,
    name: String,
    surname: String
) : Human(id, name) { /*...*/ }
```

複数のインターフェイスがあるときは、上位のクラスのコンストラクタの呼び出しを最初に置き、それから、各インターフェイスを別の行に置きます。

```kotlin
class Person(
    id: Int,
    name: String,
    surname: String
) : Human(id, name),
    KotlinMaker { /*...*/ }
```

上位の型の一覧が長いクラスは、コロンの後ろで改行し、上位の型の名前を、全て横にそろえます。

```kotlin
class MyFavouriteVeryLongClassHolder :
    MyLongHolder<MyFavouriteVeryLongClass>(),
    SomeOtherInterface,
    AndAnotherOne {

    fun foo() { /*...*/ }
}
```

クラスのヘッダーが長いとき、ヘッダーと本体をはっきり分けるには、(上の例のように) クラスのヘッダーの後ろに空行を入れるか、開き波括弧を別の行に置きます。

```kotlin
class MyFavouriteVeryLongClassHolder :
    MyLongHolder<MyFavouriteVeryLongClass>(),
    SomeOtherInterface,
    AndAnotherOne
{
    fun foo() { /*...*/ }
}
```

コンストラクタのパラメータには、通常のインデント (空白 4 つ) を使います。こうすると、主コンストラクタで宣言したプロパティが、クラスの本体で宣言したプロパティと、同じインデントになります。

#### 修飾子の順番

宣言に、複数の修飾子があるときは、常に次の順に置きます。

```kotlin
public / protected / private / internal
expect / actual
final / open / abstract / sealed / const
external
override
lateinit
tailrec
vararg
suspend
inner
enum / annotation / fun // as a modifier in `fun interface`
companion
inline / value
infix
operator
data
```

全てのアノテーションは、修飾子の前に置きます。

```kotlin
@Named("Foo")
private val foo: Foo
```

ライブラリを作っているのでなければ、冗長な修飾子 (たとえば、`public`) は省きます。

#### アノテーション

アノテーションは、付ける宣言の前に、別の行で、同じインデントで置きます。

```kotlin
@Target(AnnotationTarget.PROPERTY)
annotation class JsonExclude
```

引数のないアノテーションは、同じ行に置いてもよいです。

```kotlin
@JsonExclude @JvmField
var x: String
```

引数のないアノテーションが 1 つだけなら、対応する宣言と、同じ行に置いてもよいです。

```kotlin
@Test fun foo() { /*...*/ }
```

#### ファイルのアノテーション

ファイルのアノテーションは、ファイルのコメント (あれば) の後ろ、`package` 文の前に置き、`package` とは空行で分けます (パッケージではなく、ファイルが対象であることを強調するため)。

```kotlin
/** License, copyright and whatever */
@file:JvmName("FooBar")

package foo.bar
```

#### 関数

関数のシグネチャが 1 行に収まらないときは、次の書き方を使います。

```kotlin
fun longMethodName(
    argument: ArgumentType = defaultValue,
    argument2: AnotherArgumentType,
): ReturnType {
    // body
}
```

関数のパラメータには、通常のインデント (空白 4 つ) を使います。こうすると、コンストラクタのパラメータと、一貫させやすくなります。

本体が 1 つの式だけの関数は、式の本体を使うほうを選びます。

```kotlin
fun foo(): Int {     // bad
    return 1
}

fun foo() = 1        // good
```

#### 式の本体

関数に、式の本体があり、その最初の行が、宣言と同じ行に収まらないときは、`=` の記号を最初の行に置き、式の本体を、空白 4 つでインデントします。

```kotlin
fun f(x: String, y: String, z: String) =
    veryLongFunctionCallWithManyWords(andLongParametersToo(), x, y, z)
```

#### プロパティ

とても単純な、読み取り専用のプロパティは、1 行の整形を考えます。

```kotlin
val isEmpty: Boolean get() = size == 0
```

もっと複雑なプロパティは、常に、`get` と `set` のキーワードを、別の行に置きます。

```kotlin
val foo: String
    get() { /*...*/ }
```

初期化子のあるプロパティで、初期化子が長いときは、`=` の記号の後ろで改行し、初期化子を、空白 4 つでインデントします。

```kotlin
private val defaultCharset: Charset? =
    EncodingRegistry.getInstance().getDefaultCharsetForPropertiesFiles(file)
```

#### 制御フローの文

`if` や `when` の文の条件が、複数の行になるときは、常に、文の本体を波括弧で囲みます。条件の 2 行目からは、文の始まりから、空白 4 つでインデントします。条件の閉じ丸括弧は、開き波括弧と一緒に、別の行に置きます。

```kotlin
if (!component.isSyncing &&
    !hasAnyKotlinRuntimeInScope(module)
) {
    return createKotlinNotConfiguredPanel(module)
}
```

こうすると、条件と、文の本体をそろえやすくなります。

`else`、`catch`、`finally` のキーワードと、`do-while` のループの `while` のキーワードは、前の波括弧と同じ行に置きます。

```kotlin
if (condition) {
    // body
} else {
    // else part
}

try {
    // body
} finally {
    // cleanup
}
```

`when` の文で、分岐が 1 行より長いときは、隣の分岐のブロックと、空行で分けることを考えます。

```kotlin
private fun parsePropertyValue(propName: String, token: Token) {
    when (token) {
        is Token.ValueToken ->
            callback.visitValue(propName, token.value)

        Token.LBRACE -> { // ...
        }
    }
}
```

短い分岐は、条件と同じ行に、波括弧なしで置きます。

```kotlin
when (foo) {
    true -> bar() // good
    false -> { baz() } // bad
}
```

#### メソッドの呼び出し

引数の一覧が長いときは、開き丸括弧の後ろで改行します。引数は、空白 4 つでインデントします。密接に関係する、複数の引数は、同じ行にまとめます。

```kotlin
drawSquare(
    x = 10, y = 10,
    width = 100, height = 100,
    fill = true
)
```

引数の名前と値を区切る `=` の記号の前後に、空白を入れます。

#### 連鎖した呼び出しの折り返し

連鎖した呼び出しを折り返すときは、`.` の文字か `?.` の演算子を、次の行に、1 段のインデントで置きます。

```kotlin
val anchor = owner
    ?.firstChild!!
    .siblings(forward = true)
    .dropWhile { it is PsiComment || it is PsiWhiteSpace }
```

連鎖の最初の呼び出しは、たいてい、その前で改行しますが、そのほうがコードとして分かりやすいなら、省いてもかまいません。

#### ラムダ

ラムダ式では、波括弧の前後と、パラメータと本体を区切る矢印の前後に、空白を入れます。呼び出しが、ラムダを 1 つだけ受け取るときは、できるだけ、丸括弧の外に渡します。

```kotlin
list.filter { it > 10 }
```

ラムダにラベルを付けるときは、ラベルと開き波括弧の間に、空白を入れません。

```kotlin
fun foo() {
    ints.forEach lit@{
        // ...
    }
}
```

複数の行のラムダで、パラメータの名前を宣言するときは、名前を最初の行に置き、続けて矢印と改行を置きます。

```kotlin
appendCommaSeparated(properties) { prop ->
    val propertyValue = prop.get(obj)  // ...
}
```

パラメータの一覧が長くて、1 行に収まらないときは、矢印を別の行に置きます。

```kotlin
foo {
    context: Context,
    environment: Env
    ->
    context.configureEnv(environment)
}
```

#### 末尾のカンマ

末尾のカンマは、並んだ要素の、最後の要素の後ろにあるカンマです。

```kotlin
class Person(
    val firstName: String,
    val lastName: String,
    val age: Int, // trailing comma
)
```

末尾のカンマを使うと、いくつかの利点があります。

* バージョン管理の差分が、きれいになります (変わった値だけに、注目できる)。
* 要素の追加や、並べ替えが、簡単になります (要素を操作するときに、カンマを足したり消したりしなくてよい)。
* コードの生成 (たとえば、オブジェクトの初期化子) が、簡単になります。最後の要素にも、カンマを付けられます。

末尾のカンマは、完全に任意です。なくても、コードは動きます。Kotlin のスタイルガイドは、宣言する側では、末尾のカンマを使うことを勧め、呼び出す側では、判断に任せます。

IntelliJ IDEA の整形で、末尾のカンマを有効にするには、**Settings/Preferences | Editor | Code Style | Kotlin** を開き、**Other** のタブで、**Use trailing comma** の設定を選びます。

##### 列挙

```kotlin
enum class Direction {
    NORTH,
    SOUTH,
    WEST,
    EAST, // trailing comma
}
```

##### 値の引数

```kotlin
fun shift(x: Int, y: Int) { /*...*/ }
shift(
    25,
    20, // trailing comma
)
val colors = listOf(
    "red",
    "green",
    "blue", // trailing comma
)
```

##### クラスのプロパティとパラメータ

```kotlin
class Customer(
    val name: String,
    val lastName: String, // trailing comma
)
class Customer(
    val name: String,
    lastName: String, // trailing comma
)
```

##### 関数の値のパラメータ

```kotlin
fun powerOf(
    number: Int,
    exponent: Int, // trailing comma
) { /*...*/ }
constructor(
    x: Comparable<Number>,
    y: Iterable<Number>, // trailing comma
) {}
fun print(
    vararg quantity: Int,
    description: String, // trailing comma
) {}
```

##### 型を省ける (セッターを含む) パラメータ

```kotlin
val sum: (Int, Int, Int) -> Int = fun(
    x,
    y,
    z, // trailing comma
): Int {
    return x + y + x
}
println(sum(8, 8, 8))
```

##### インデックスの接尾辞

```kotlin
class Surface {
    operator fun get(x: Int, y: Int) = 2 * x + 4 * y - 10
}
fun getZValue(mySurface: Surface, xValue: Int, yValue: Int) =
    mySurface[
        xValue,
        yValue, // trailing comma
    ]
```

##### ラムダのパラメータ

```kotlin
fun main() {
    val x = {
            x: Comparable<Number>,
            y: Iterable<Number>, // trailing comma
        ->
        println("1")
    }
    println(x)
}
```

##### when の項目

```kotlin
fun isReferenceApplicable(myReference: KClass<*>) = when (myReference) {
    Comparable::class,
    Iterable::class,
    String::class, // trailing comma
        -> true
    else -> false
}
```

##### (アノテーションの中の) コレクションのリテラル

```kotlin
annotation class ApplicableFor(val services: Array<String>)
@ApplicableFor([
    "serializer",
    "balancer",
    "database",
    "inMemoryCache", // trailing comma
])
fun run() {}
```

##### 型の引数

```kotlin
fun <T1, T2> foo() {}
fun main() {
    foo<
            Comparable<Number>,
            Iterable<Number>, // trailing comma
            >()
}
```

##### 型のパラメータ

```kotlin
class MyMap<
        MyKey,
        MyValue, // trailing comma
        > {}
```

##### 分解宣言

```kotlin
data class Car(val manufacturer: String, val model: String, val year: Int)
val myCar = Car("Tesla", "Y", 2019)
val (
    manufacturer,
    model,
    year, // trailing comma
) = myCar
val cars = listOf<Car>()
fun printMeanValue() {
    var meanValue: Int = 0
    for ((
        _,
        _,
        year, // trailing comma
    ) in cars) {
        meanValue += year
    }
    println(meanValue/cars.size)
}
printMeanValue()
```

### ドキュメントのコメント

長いドキュメントのコメントは、開きの `/**` を別の行に置き、続く各行を、アスタリスクで始めます。

```kotlin
/**
 * This is a documentation comment
 * on multiple lines.
 */
```

短いコメントは、1 行に置けます。

```kotlin
/** This is a short documentation comment. */
```

一般に、`@param` と `@return` のタグは、避けます。代わりに、パラメータと戻り値の説明を、ドキュメントのコメントに直接組み込み、パラメータに触れるところには、パラメータへのリンクを付けます。`@param` と `@return` は、本文の流れに収まらない、長い説明が要るときだけ使います。

(このプロジェクトでは、上の「このプロジェクトでの違い」のとおり、`@param` と `@return` を書きます。)

```kotlin
// Avoid doing this:

/**
 * Returns the absolute value of the given number.
 * @param number The number to return the absolute value for.
 * @return The absolute value.
 */
fun abs(number: Int): Int { /*...*/ }

// Do this instead:

/**
 * Returns the absolute value of the given [number].
 */
fun abs(number: Int): Int { /*...*/ }
```

### 冗長な構文を避ける

一般に、Kotlin のある構文が省略でき、IDE が冗長として強調するなら、コードから省きます。「分かりやすさのため」だけに、不要な構文の要素を、コードに残しません。

#### Unit の戻り値の型

関数が Unit を返すときは、戻り値の型を省きます。

```kotlin
fun foo() { // ": Unit" is omitted here

}
```

#### セミコロン

できる限り、セミコロンを省きます。

#### 文字列テンプレート

単純な変数を、文字列テンプレートに入れるときは、波括弧を使いません。波括弧は、もっと長い式のときだけ使います。

```kotlin
println("$name has ${children.size} children")
```

ドルの記号 `$` を、文字列のリテラルとして扱うには、[複数のドル記号の文字列補間](https://kotlinlang.org/docs/strings.html#multi-dollar-string-interpolation) を使います。

```kotlin
val KClass<*>.jsonSchema : String
    get() = $$"""
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$id": "https://example.com/product.schema.json",
            "$dynamicAnchor": "meta",
            "title": "$${simpleName ?: qualifiedName ?: "unknown"}",
            "type": "object"
        }
        """
```

### 言語の機能の、慣用的な使い方

#### 不変性

変更できるデータより、不変のデータを使うほうを選びます。初期化のあとに変更しないローカル変数とプロパティは、常に、`var` ではなく `val` で宣言します。

変更しないコレクションの宣言には、常に、不変のコレクションのインターフェイス (`Collection`、`List`、`Set`、`Map`) を使います。ファクトリ関数でコレクションのインスタンスを作るときは、できる限り、常に、不変のコレクションの型を返す関数を使います。

```kotlin
// Bad: use of a mutable collection type for value which will not be mutated
fun validateValue(actualValue: String, allowedValues: HashSet<String>) { ... }

// Good: immutable collection type used instead
fun validateValue(actualValue: String, allowedValues: Set<String>) { ... }

// Bad: arrayListOf() returns ArrayList<T>, which is a mutable collection type
val allowedValues = arrayListOf("a", "b", "c")

// Good: listOf() returns List<T>
val allowedValues = listOf("a", "b", "c")
```

#### パラメータの既定値

オーバーロードした関数を宣言するより、パラメータの既定値のある関数を宣言するほうを選びます。

```kotlin
// Bad
fun foo() = foo("a")
fun foo(a: String) { /*...*/ }

// Good
fun foo(a: String = "a") { /*...*/ }
```

#### 型の別名

関数の型や、型パラメータのある型を、コードの中で何度も使うときは、型の別名を定義するほうを選びます。

```kotlin
typealias MouseClickHandler = (Any, MouseEvent) -> Unit
typealias PersonIndex = Map<String, Person>
```

名前の衝突を避けるために、private や internal の型の別名を使うときは、[Packages and imports](https://kotlinlang.org/docs/packages.html) にある、`import ... as ...` を使うほうを選びます。

#### ラムダのパラメータ

短く、ネストしていないラムダでは、パラメータを明示的に宣言する代わりに、`it` の慣例を使うことを勧めます。パラメータのある、ネストしたラムダでは、常に、パラメータを明示的に宣言します。

#### ラムダの中の return

ラムダの中で、ラベル付きの return を、複数使うのは避けます。出口が 1 つになるように、ラムダの構成を変えることを考えます。それができないか、十分に分かりやすくならないときは、ラムダを、無名関数に変えることを考えます。

ラムダの最後の文に、ラベル付きの return を使いません。

#### 名前付きの引数

メソッドが、同じプリミティブ型のパラメータを複数受け取るときや、`Boolean` の型のパラメータには、全てのパラメータの意味が、文脈から完全に明らかでない限り、名前付きの引数の書き方を使います。

```kotlin
drawSquare(x = 10, y = 10, width = 100, height = 100, fill = true)
```

#### 条件の文

`try`、`if`、`when` は、式の形を使うほうを選びます。

```kotlin
return if (x) foo() else bar()
```

```kotlin
return when(x) {
    0 -> "zero"
    else -> "nonzero"
}
```

上の書き方は、次の書き方より望ましいです。

```kotlin
if (x)
    return foo()
else
    return bar()
```

```kotlin
when(x) {
    0 -> return "zero"
    else -> return "nonzero"
}
```

#### if と when

2 択の条件には、`when` ではなく、`if` を使うほうを選びます。たとえば、`if` を使う、次の書き方にします。

```kotlin
if (x == null) ... else ...
```

`when` を使う、次の書き方ではなく。

```kotlin
when (x) {
    null -> // ...
    else -> // ...
}
```

選択肢が 3 つ以上あるときは、`when` を使うほうを選びます。

#### when の式のガード条件

[ガード条件](https://kotlinlang.org/docs/control-flow.html#guard-conditions-in-when-expressions) のある `when` の式や文で、複数の真偽値の式を組み合わせるときは、丸括弧を使います。

```kotlin
when (status) {
    is Status.Ok if (status.info.isEmpty() || status.info.id == null) -> "no information"
}
```

次の書き方ではなく。

```kotlin
when (status) {
    is Status.Ok if status.info.isEmpty() || status.info.id == null -> "no information"
}
```

#### 条件の中の、null を許す Boolean の値

条件の文で、null を許す `Boolean` を使う必要があるときは、`if (value == true)` か `if (value == false)` で確かめます。

#### ループ

ループより、高階関数 (`filter`、`map` など) を使うほうを選びます。例外: `forEach` (`forEach` のレシーバーが null を許すか、`forEach` を、長い呼び出しの連鎖の一部として使うのでなければ、通常の `for` のループを使うほうを選びます)。

複数の高階関数を使う複雑な式と、ループのどちらかを選ぶときは、それぞれの場合に行われる操作のコストを理解して、性能を考えに入れます。

#### 範囲のループ

終わりを含まない範囲のループには、`..<` の演算子を使います。

```kotlin
for (i in 0..n - 1) { /*...*/ }  // bad
for (i in 0..<n) { /*...*/ }  // good
```

#### 文字列

文字列の連結より、文字列テンプレートを使うほうを選びます。

通常の文字列のリテラルに、`\n` のエスケープシーケンスを埋め込むより、複数行の文字列を使うほうを選びます。

複数行の文字列で、インデントを保つには、結果の文字列に、中のインデントが要らないときは `trimIndent` を、中のインデントが要るときは `trimMargin` を使います。

```kotlin
fun main() {
//sampleStart
    println("""
     Not
     trimmed
     text
     """
    )

    println("""
     Trimmed
     text
     """.trimIndent()
    )

    println()

    val a = """Trimmed to margin text:
            |if(a > 1) {
            |    return a
            |}""".trimMargin()

   println(a)
//sampleEnd
}
```

[Java と Kotlin の複数行の文字列](https://kotlinlang.org/docs/java-to-kotlin-idioms-strings.html#use-multiline-strings) の違いを知ってください。

#### 関数とプロパティ

場合によっては、引数のない関数と、読み取り専用のプロパティを、入れ替えられることがあります。意味は似ていますが、どちらを選ぶかについて、スタイルの上の慣例があります。

元になる処理が、次のときは、関数よりプロパティを選びます。

* 例外を投げない。
* 計算が軽い (または、最初の実行でキャッシュする)。
* オブジェクトの状態が変わっていなければ、呼び出すたびに、同じ結果を返す。

#### 拡張関数

拡張関数は、遠慮なく使います。主に、あるオブジェクトに対して働く関数があるときは、いつも、そのオブジェクトをレシーバーとして受け取る、拡張関数にすることを考えます。API を汚すのを最小にするため、拡張関数の可視性は、意味のある範囲で、できるだけ制限します。必要に応じて、ローカルの拡張関数、メンバーの拡張関数、private の可視性の、トップレベルの拡張関数を使います。

#### 中置関数

関数を `infix` で宣言するのは、似た役割の 2 つのオブジェクトに対して働くときだけです。よい例: `and`、`to`、`zip`。悪い例: `add`。

レシーバーのオブジェクトを変更するメソッドは、`infix` で宣言しません。

#### ファクトリ関数

クラスのファクトリ関数を宣言するときは、クラスそのものと同じ名前にするのは避けます。ファクトリ関数の振る舞いが、なぜ特別なのかがはっきりする、別の名前を使うほうを選びます。本当に特別な意味がないときだけ、クラスと同じ名前を使ってよいです。

```kotlin
class Point(val x: Double, val y: Double) {
    companion object {
        fun fromPolar(angle: Double, radius: Double) = Point(...)
    }
}
```

オブジェクトに、オーバーロードした複数のコンストラクタがあり、それらが、上位のクラスの別のコンストラクタを呼ばず、既定値のあるパラメータを含む 1 つのコンストラクタにまとめられないときは、オーバーロードしたコンストラクタを、ファクトリ関数に置き換えるほうを選びます。

#### プラットフォーム型

プラットフォーム型の式を返す、公開の関数やメソッドは、Kotlin の型を明示的に宣言しなければなりません。

```kotlin
fun apiCall(): String = MyJavaApi.getProperty("name")
```

プラットフォーム型の式で初期化する、どのプロパティ (パッケージのレベルか、クラスのレベル) も、Kotlin の型を明示的に宣言しなければなりません。

```kotlin
class Person {
    val name: String = MyJavaApi.getProperty("name")
}
```

プラットフォーム型の式で初期化する、ローカルの値は、型の宣言があっても、なくてもかまいません。

```kotlin
fun main() {
    val name = MyJavaApi.getProperty("name")
    println(name)
}
```

#### スコープ関数 apply/with/run/also/let

Kotlin には、与えたオブジェクトの文脈で、コードのブロックを実行する関数の組、`let`、`run`、`with`、`apply`、`also` があります。場合に合ったスコープ関数の選び方は、[Scope functions](https://kotlinlang.org/docs/scope-functions.html) を見てください。

### ライブラリのためのコーディング規約

ライブラリを書くときは、API の安定を保つため、追加の規則に従うことを勧めます。

 * メンバーの可視性を、常に明示的に指定します (宣言を、うっかり公開の API にしないため)。
 * 関数の戻り値の型と、プロパティの型を、常に明示的に指定します (実装を変えたときに、うっかり戻り値の型を変えないため)。
 * 新しいドキュメントが要らないオーバーライドを除き、全ての公開のメンバーに、[KDoc](https://kotlinlang.org/docs/kotlin-doc.html) のコメントを付けます (ライブラリのドキュメントの生成を支えるため)。

ライブラリの API を書くときの、ベストプラクティスと考えるべきことは、[Library authors' guidelines](https://kotlinlang.org/docs/api-guidelines-introduction.html) で詳しく知ってください。
