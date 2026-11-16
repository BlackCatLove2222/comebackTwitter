# src/ — 自作ヘルパーのソース

## Arc.java — プル・トゥ・リフレッシュ円弧オーバーレイ

`Arc.java` は、プル・トゥ・リフレッシュの円弧（白バッジ＋リング＋矢じり）を、アプリの画面（Activity の decorView）上に**オーバーレイ View として分離描画**するための自作ヘルパーです。

- 配置先: パッチ済み APK の **classes6.dex** に、`Lpiko/jp15/Arc;`（パッケージ `piko.jp15`）として追加する
- 描画: 素の `View` ＋ カスタム `Drawable`（Canvas の drawArc / drawCircle、事前レンダリングしたブラー影 Bitmap）。タッチは消費しない・フォーカスなし
- 動きの供給: フレームループ（Choreographer）が refresh 状態ホルダーの値を直接読むため、指を離した後のスプリングバックにも追随できる
- パラメータ: すべてクラス内の定数。値と意味は [../SPEC.md](../SPEC.md) §10 の表が正（TAB_BASE_DP=105、出現 +81dp、飽和 +70dp、ホールド +101 / 1300ms、リング Ø18 × 2.5、矢じり 10×5dp 等）
- 安全設計: 全エントリポイントが例外を握り潰す（最悪でも「円弧が出ない」だけ）、Activity は弱参照、HOLD 20 秒のフェイルセーフ、PiP 中は非表示

### コンパイル

```sh
javac -source 8 -target 8 -cp <android.jar と依存スタブ> -d classes piko/jp15/Arc.java
d8 --min-api 29 --output dexout classes
```

`-source 8 -target 8`（class major 52）を明示すること。新しめの JDK のデフォルトでコンパイルすると nestmate 橋渡し方式に化けて差分が膨らむ。

### フック（3点、いずれも平文コードへの単一呼び出し挿入）

| フック | 場所 | 内容 |
|---|---|---|
| H1 | classes18: `Lcom/x/android/main/MainActivity;->onStart` | `invoke-super` の直後に `Arc.attach(p0)` を1命令挿入。レジスタ書き換えなし（p0 は読み取りのみ） |
| H2 | classes6: `com/x/home/tabbed/refresh/g` の `invokeSuspend` | 入口のプレアンブルで `Arc.pull(l, fraction)`（対象の refresh 状態ホルダーとプル分率を供給）。書き込むレジスタは元プロローグが読み取り前に全て上書きするものだけ |
| H3 | classes6: `com/x/home/tabbed/refresh/m` の `invokeSuspend` | 更新音フック（[../BUILD.md](../BUILD.md) B9 の `Snd.edge`）の直後に `Arc.edge(収集Boolean)` を1命令追加。新規レジスタ使用なし |

アプリ本体の合成（Compose）には一切触れません。ヘッダーのロゴ枠には鳥だけが残り（バンドル同梱の単層・静止鳥ファイル。入手と塗り色は [../BUILD.md](../BUILD.md) 手順 B10）、円弧はこのヘルパーだけが描きます。

## AppearanceSync.smali / BuiltInSettings-registerAppearance.smali — jp21「外観」セレクタ

jp21 の統合「外観」セレクタ（[../SPEC.md](../SPEC.md) §14）を手ビルドで施工した際の実物 smali です。

- **AppearanceSync.smali**: 起動時同期クラスの全文。配置先はパッチ済み APK の **classes21.dex**、`Lapp/morphe/extension/newx/theme/AppearanceSync;`
- **BuiltInSettings-registerAppearance.smali**: 設定登録の追加分（`register()` 末尾への呼び出し1行＋ `registerAppearance()` メソッド）の抜粋。本体は生成コードのため全文は同梱しません
- ほかに `NewXSettingsHost.install()` の末尾へ `AppearanceSync.sync()` の呼び出し追加（2行）が必要です。手順の全体は [../BUILD.md](../BUILD.md) の B14
- バンドル形では、同等物をパッチプロジェクト側の Java ソースとして入れます（ラベルは専用文字列 `piko_newx_appearance_*` を使用）。手ビルド版は arsc 追加を避けるため一部のラベルに既存文字列を流用しています

## ReplySortingSync.smali / ReplySortingResolver-getDefault.smali — jp22 関連性ピン留め

jp22 の返信ソート関連性ピン留め（[../SPEC.md](../SPEC.md) §16、[../BUILD.md](../BUILD.md) B15）のうち、拡張側（classes21）の実物です。

- **ReplySortingSync.smali**: 保存済み設定値を `"Relevance"` に一度だけ書き換える起動時同期クラスの全文。配置先は **classes21.dex**、`Lapp/morphe/extension/newx/misc/ReplySortingSync;`。ほかに `NewXSettingsHost.install()` の末尾へ呼び出し追加（AppearanceSync の直後）が必要です
- **ReplySortingResolver-getDefault.smali**: 解決役 `ReplySortingResolver.getDefault()` を無条件で `"Relevance"` を返す形に置き換えたメソッドの抜粋
- 登録既定値の復元（B15 の1点目）は classes19 側の生成登録コードの編集であり、本節の同梱物には含みません（手順は BUILD.md B15）

## HeaderRowSnippets.smali — jp23 引用数ピボット／ソートタブ除去

jp23 の詳細ヘッダ行まわり（[../SPEC.md](../SPEC.md) §17、[../BUILD.md](../BUILD.md) B16 / B17）の実物抜粋集です。X 本体側の変更のため、全文ではなく**追加・変更した命令列の断片**を対象メソッドごとに収録しています:

- 断片1（B17）: 行描画メソッド内の **goto スキップ＋生存レジスタ再現12命令**（jp23.1 の納品形）。⚠ 再現命令列なしでスキップだけを行うと VerifyError で起動不能になります。実施条件と検証要件は BUILD.md B17 を必ず参照してください
- 断片2（B16）: マッパ（classes6）への Quote エントリ追加
- 断片3〜5（B16）: 焦点状態の組み立て（classes7）における Quote 件数の取得ループ、showPivot への OR、ピボットラベルの差し替え

## 同梱していないもの

- **更新音ヘルパー（`Lpiko/jp8/Snd;`）**: 音源（Twitter 8.65 の `psst2.ogg` / `pop.ogg`）をバイト配列として埋め込む設計のため、音源の権利の関係でソース・バイナリとも同梱しません。再現方法は [../BUILD.md](../BUILD.md) の手順 B9 を参照してください（構造は単純で、SoundPool で2音を状態エッジで鳴らすだけです）
