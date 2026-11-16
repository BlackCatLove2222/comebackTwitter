# BUILD — 適用・ビルド手順

comebackTwitter の jp23 仕様を再現する手順です。全体は2段階:

- **手順 A（バンドルで完結）**: Morphe でパッチバンドルを当てる。日本語化と基本構成はここまでで入る
- **手順 B（後処理）**: パッチ済み APK に対して、バンドルでは表現できない変更を dex / リソースレベルで追加する。jp23 仕様の残りはほぼすべてここ（例外: B14 はバンドル表現が正規形、B15 は一部がバンドル表現可能。B17 は任意の上級手順）

最後に **手順 C（署名）**。仕様の数値は [SPEC.md](SPEC.md) が正です。

> 前提: 本家 X の APK は各自で用意してください（配布していません）。署名鍵も各自のものを用意し、全バージョンで同一鍵を使い続けてください（鍵が変わると上書き更新できません）。鍵そのものは本リポジトリに含まれません。

## 必要なもの

| もの | 用途 |
|---|---|
| X 12.41.0-prod.01 の APKM（arm64-v8a、Android 10+ 向けバリアント） | パッチ対象 |
| Morphe CLI（morphe-desktop）または Morphe Manager | 手順 A |
| JDK（バンドル編集・ヘルパーのコンパイル用） | 手順 B の一部 |
| apktool / baksmali・smali、Android build-tools（zipalign / apksigner / aapt2） | 手順 B・C |
| Twitter 8.65.0-release.00 の APK | 手順 B9 の音源抽出用（各自で入手） |

## 手順 A — バンドルで完結する部分

同梱物:

- `patches/patches-3.75.0-jp.mpp` — 上流 piko-newx v3.75.0 バンドルに (1) 日本語リソース `values-ja-rJP/newx/strings.xml`（486 件）を追加し、(2) 拡張 dex（`extensions/newx.mpe` / `extensions/shared.mpe`）内のハードコード表示文字列 135 種を翻訳したもの
  - SHA-256: `d71823a80a3e069e45013a2416c59ec32cf035cdc79bdb8e2ebb2fa2e93902ba`
- `patches/options-jp.json` — パッチ選択とオプション（**Clone = 有効**（パッケージ名 `com.twitter.android.piko`）、**Restore Twitter branding = 有効**（ラベル「Twitter」）。「Server error logging」は無効のまま）

CLI の例:

```sh
java -jar morphe-desktop-<version>-all.jar patch \
  -p patches/patches-3.75.0-jp.mpp \
  --options-file=patches/options-jp.json \
  -o patched.apk \
  <自分の X 12.41.0-prod.01 .apkm>
```

期待結果: **48 パッチ適用 / 失敗 0**。この時点で、パッケージ名・ラベル・日本語化（リソース＋拡張文字列）は仕様どおりになる。

## 手順 B — 後処理（バンドル外の変更）

以下はパッチ済み APK への後処理です。各項目に「対象（dex / クラス・メソッド）」「変更内容」を記します。**全項目に共通する注意**:

- 変更は [SPEC.md](SPEC.md) §11 の安全原則（実証済みカテゴリのみ。コンポーザブルへの命令挿入・共有レジスタ反転は禁止）を守ること
- 各段階の後に zip エントリ単位の差分照合を行い、意図したエントリだけが変わっていることを確認すること
- 設定デフォルトの変更（B1 等）は「新規インストール / データ消去時に有効、保存済みの値があればそちら優先」という意味論です

### B1. 設定デフォルトの焼き込み（classes19 / classes21）

生成済み設定レジストリと各 Config クラスのデフォルト値を、[SPEC.md](SPEC.md) §4 の表どおりに書き換える:

- レジストリ側（`SettingsRegistry`）: `hide_add_tab_button=true`、`for_you_filtering.tab_hook=false`、`hide_who_to_follow=true`、`hidden_inline_actions` の `ViewCount` を初期選択、`custom_sharing_domain="twitter.com"`、`default_reply_sorting="Recency"`（選択マーカーも連動。**jp22 以降は B15 で `"Relevance"` に変更する**のが現行仕様）、`hide_spaces_bar=true`、`dark_style="dim"`（選択マーカーも連動）、`hidden_drawer_items` の初期選択を §4 の12項目に（**リストは選択しない**）
- 拡張所有の設定（各 Config の初期化）: ナビの隠し項目 `"GROK"`、プロフィールタブの隠しリスト `"AffiliatedUsers,Reposts,SuperFollowers,Highlights,Articles"`
- ドロワーの「作業を自動化」（`RESOURCE_NAME_drawer_grok_bot_automate_work`）も初期選択に追加する
- コミュニティは非表示にしない（初期選択から外しておく）

### B2. フィーチャースイッチ上書きの初期シード（classes21）

`FeatureSwitchStore` の保存用文字列設定（キー `newx.advanced.feature_switches.overrides`）のデフォルト値を、以下の正規形 JSON にする:

```json
{"version":1,"overrides":[{"key":"android_history_enabled","type":"BOOLEAN","value":false},{"key":"x_lite_notifications_disabled_settings_callout_enabled","type":"BOOLEAN","value":false},{"key":"x_lite_notifications_permission_fullscreen_prompt_enabled","type":"BOOLEAN","value":false}]}
```

### B3. XApplication への patch override 登録（classes18）

`XApplication.onCreate` の注入ブロック（NewX 自身が登録している箇所）に、既存と同形式で [SPEC.md](SPEC.md) §5 の9件を登録する。特に `hometimeline_pinned_tabs_topics_enabled=false` が「追加」タイル消去の決定打（他の2件は設定 UI と描画様式用で、タイル本体には効かない）。

### B4. 旧式「追加」ボタンの消費側ゲート強化（classes18）

ホームタブの旧式追加ボタン（`com/x/home/tabbed/a` の当該コンポーザブル）が設定を読む箇所を、フォールバック `true` の2引数版 `getBooleanOrDefault(key, true)` に変える（どの解決経路でも隠れたままにするため）。

### B5. 用語置換とクレジット（resources.arsc）

文字列プールの**表示値だけ**を置換する（キー・書式指定子・ styled 文字列には触れない）:

- ja: リポスト→リツイート、引用ポスト→引用ツイート、ポスト→ツイート の順で置換（376 件）。除外: ポストシーズン、ローカライズ検証用フィクスチャ
- en（デフォルト構成）: 単語境界で Post/Posts/Posted/Posting→Tweet 系、Repost 系→Retweet 系（30 件）。除外: `Post or Town`（住所ラベル）
- `string/piko_newx_patch_version` の値を **`Piko %s · ミスティ`** に（デフォルト・ja 両構成）
- `string/joined_date` の ja 値を **`{date}からTwitterを利用しています`** に（1件）

### B6. プロフィールヘッダーの Grok / 検索アイコン除去（classes6）

プロフィールヘッダーの描画（`com/x/profile/header/b`）で、末尾アクション（grok / search）が2モードから合流する直後の地点で両者を null 化する。下流は「null なら描画しない」既存分岐のみで、戻る矢印・名前・⋮（overflow）には触れない。

### B7. ドロワー下段の上寄せ（classes17）

ドロワー合成（`com/x/main/drawer/k`）で、主項目直後の重み付きスペーサー（weight 1.0f）が下段を画面下端へ押し下げている。そのスペーサーへ渡す修飾子を空の Modifier に差し替え、下段（設定とプライバシー / Piko設定 / ヘルプセンター）が主項目の直下に流れるようにする。命令の挿入・ブロック移動はしない。

### B8. 自分プロフィールの「共有」ボタン除去（classes18）

プロフィールヘッダー状態を生成する箇所で、自分プロフィール分岐のプライマリアクション列が {共有, プロフィールを編集} で構築される。その列から共有（`button_action_share`）の要素だけを取り除き、{プロフィールを編集} のみにする。

### B9. 更新音（classes6）

1. Twitter 8.65.0-release.00 の APK（各自で入手）を展開し、`res/raw/psst2.ogg` と `res/raw/pop.ogg` を取り出す
2. 再生ヘルパー（新規クラス `Lpiko/jp8/Snd;`）を用意する: 2つの OGG をバイト配列として埋め込み、初回呼び出し時にキャッシュへ書き出して SoundPool（STREAM_MUSIC、音量 0.7）にロード。状態エッジでのみ再生し、初回観測はベースラインのみ、300ms 未満の連打は抑止、例外はすべて内部で握り潰す
3. フック: タブホーム PTR の Flow コレクタ（`com/x/home/tabbed/refresh/m` の `invokeSuspend`）の入口に、収集値の初回入口でのみ `Snd.edge(収集Boolean)` を呼ぶ呼び出しを挿入する。挿入部が書くレジスタは、直後の元命令が読み取り前に再代入するものだけに限ること（レジスタ安全性の証明が必須）
4. 結線の根拠: 8.65 本体でも開始遷移→psst2 / 完了遷移→pop であり、耳での同定とも一致する

※ 音源ファイルと、それを埋め込んだヘルパーのバイナリは**配布しません**。各自の 8.65 APK から抽出してください。

### B10. プル・トゥ・リフレッシュ（classes6 / classes18 / リソース）

1. **ロゴ枠の鳥**: `res/raw/x_refresh_logo.json` は、手順 A で「Restore Twitter branding」を有効にして当てると、**バンドル自体が同梱する単層・静止鳥ファイルにすでに差し替わっている**（バンドル内パス `twitter/bringbacktwitter/raw/x_refresh_logo.json`）。ファイルの構成は: キャンバス 240×240、単一レイヤー「X Rest」、鳥パス（22 頂点）のみ、レイヤー／グループの transform は identity、キーフレームなし（静止画）。このファイル自体は本リポジトリでは**配布しない**（鳥パスが X のロゴ意匠のため。各自の APK に手順 A で入るものを使ってください）。comebackTwitter の実ビルドとの差分は**塗り色のみ**: バンドル版の黒 `[0,0,0,1]` を Twitter ブルー **#1D9BF0**（`[0.114,0.608,0.941,1]`）に変える。手順 A 適用後の APK から当該 JSON を取り出して塗り色を書き換え、戻すこと（レイヤー名「X Rest」は実行時の色上書きキーパスの対象なので変えないこと）
2. **ヘッダー下降の等倍化**: プル時にヘッダー群を描画平行移動させる箇所（`com/x/home/tabbed/refresh/q` のヘッダー側分岐）にある除数リテラル `2.0f` を `1.0f` に置換する（定数1件のみ）
3. **円弧オーバーレイ**: `src/Arc.java` をコンパイル（`-source 8 -target 8`、d8 で dex 化）し、`Lpiko/jp15/Arc;` として classes6 に追加する。フックは3点（詳細は `src/README.md`）:
   - H1: `MainActivity.onStart` の invoke-super 直後に `Arc.attach(Activity)`（classes18、レジスタ書き換えなし）
   - H2: refresh 状態フィード（`refresh/g` の `invokeSuspend`）入口に `Arc.pull(l, fraction)`（classes6）
   - H3: B9 の音フックの直後に `Arc.edge(Boolean)`（classes6）
4. パラメータはすべて Arc 内の定数（[SPEC.md](SPEC.md) §10 の表が正）

### B11. ラベル消去の仕上げ（classes18 / classes7）

- **Made on X**: B3 の2フラグ（`media_upload_video_editor_edited_on_x_label_enabled` / `media_upload_photo_editor_edited_on_x_label_enabled`）で完了。追加のコード変更は不要
- **「Grokで作成」**: フラグ方式が存在しないため、判定役 `Lcom/x/ui/common/media/attribution/h;->p(...)`（classes7、平文コード）の**先頭4バイトだけ**を `const/4 v0, 0x0` ＋ `return-object v0` に書き換えるバイナリパッチを行う（対象命令の元のバイト列を施工前に必ず照合すること）。その後 dex の SHA-1 署名と Adler-32 チェックサムを再計算する。以降の本体は到達不能なデッドコードとして残る
  - 効果は「ドメインリストが空」というアプリ標準の未設定状態と等価。詳細・統合カードの両面でラベル行が合成されなくなる
  - 写真のユーザータグ付け・「AIで生成」開示は別系統のため影響しない

### B12. 純黒テーマ「ブラック」（jp19、classes21 / resources.arsc）

1. **動的色からの分離（classes21）**: `app/morphe/extension/newx/theme/DynamicColorPalette` の `isEnabled()` に、既存の dim / default 除外と並べて **contrast 除外**を追加する。新設ヘルパー `useContrastTheme()` は `useDimTheme()` / `useDefaultTheme()` と全く同型（現在の dark_style が "contrast" かを返す）。これで dark_style=contrast のとき、拡張のパレット経路は本体のネイティブパレット＋既存の色スタンプフックを通り、contrast 用の背景表（背景 #000000、浮いた面 #121314 等）がそのまま効く。動的色の副作用は Dim 時と同様に全て OFF になる。Material You（material）の経路には触れない
2. **改名（resources.arsc）**: 文字列プールの表示値だけを変更する — `piko_newx_theme_contrast` の en を "Material You (High Contrast)" → **"Black"**、ja を "Material You（高コントラスト）" → **「ブラック」**、同項目の summary（en / ja）も実際の意味（標準パレット＋背景だけ純黒）に書き換える。プール全数照合で、変更が対象の4文字列だけであることを確認すること

### B13. 「返信先」行の復活（jp20、classes19）

状態ビルダー `Lcom/x/urt/items/post/w5;->p(...)`（平文コード）で、decider `Lcom/x/ui/common/media/attribution/h;->A(...)` の戻り値が格納されたレジスタの**唯一の読み取り箇所**にある分岐 `if-eqz vXX, :cond_*` を、無条件の `goto :cond_*` に変える（変更は1命令のみ）。これで会話系モジュールでも replyContext の実モデルが UI 状態に常時渡る。decider 自体と呼び出しには触れない。null 代入ブロックは到達不能になるが、最小差分のために残置する。

※ 行が実際に表示されるかはサーバー応答のデータ依存（[SPEC.md](SPEC.md) §15）。通知タブでは表示され、ツイート詳細（スレッド）では現行のサーバー応答に材料が無いため表示されない。これは本手順の不備ではない。

### B14. 統合「外観」セレクタ（jp21、拡張ソース — **.mpp バンドルで表現可能**）

本項目は後処理ではなく、**パッチプロジェクト側の拡張ソース変更として .mpp バンドルにそのまま入れられる**のが正規形（X 本体のクラスへのバイトコードパッチは一切不要）。変更は3点:

1. **設定の登録**（`BuiltInSettings`）: 生成コードの設定登録と同型のブロックで、単一選択設定を登録する — `registerSingleChoice("newx.appearance.theme", "newx.theme.appearance", <タイトル文字列>, <概要文字列>, order=50, rebootApp=true)`、`configureSingleChoice("newx.theme.appearance", "system", true)`、選択肢 `light` / `dim` / `black` / `system`（`system` に既定印）。登録は contributor 経由で生成登録の後・freeze の前に実行されるため、生成グループ `newx.appearance.theme` への相乗りが成立する。ブロック全体を try/catch で包み、登録失敗が起動を壊さないこと（fail-open）
2. **同期の呼び出し**（`NewXSettingsHost.install()`）: 末尾に `AppearanceSync.sync()` の呼び出しを追加する。install() は `XApplication.onCreate` から `SettingsRegistry.load()` より前に呼ばれるため、同期書き込みは全コンシューマより先に着地する
3. **同期クラス**（新規 `AppearanceSync`、theme パッケージ）: `newx.theme.appearance` の現在値を読み、未設定または適用済みマーカー `newx.theme.appearance.synced` と同値なら何もしない（ワンショット意味論）。異なる場合のみ、[SPEC.md](SPEC.md) §14 の対応表どおりに本体 pref（`dark_mode_state` / `dark_mode_appearance`）と拡張の `dark_style` を書き込み、マーカーを更新する。全体を try/catch で包む

ラベル文字列は、バンドル形では専用の `piko_newx_appearance_*` をパッチのリソースに追加する。

**手ビルドで同等にする場合**: classes21 の smali 編集で同じ3点を施工できる。実物は `src/AppearanceSync.smali`（同期クラス全文）と `src/BuiltInSettings-registerAppearance.smali`（登録ブロックの抜粋）。手ビルドでは arsc への文字列追加を避けるため、ラベルの一部に既存文字列を流用している: ライト = `grok_settings_light`、端末の設定を使う = `settings_dark_mode_system`（ダークブルー / ブラックは `piko_newx_theme_dim` / `piko_newx_theme_contrast` を共用）。この場合の差分は classes21 のみになる。

### B15. 返信ソートの関連性ピン留め（jp22、classes19 / classes21）

ツイート詳細の返信ソートを関連性（Relevance）に恒久固定する（[SPEC.md](SPEC.md) §16）。変更は3点:

1. **登録既定値の復元（classes19、`SettingsRegistry`）**: B1 で焼いた `newx.timeline.default_reply_sorting` の登録既定を `"Recency"` → **`"Relevance"`** に戻し、選択肢マーカーも連動させる（Relevance を 0x1、Recency を 0x0 に）。この登録コードはバンドルの生成コード側にあるため、パッチプロジェクト側では**登録定義のデフォルト変更として表現できる**
2. **解決役の無条件化（classes21、拡張側）**: `ReplySortingResolver.getDefault()` の本体を、`"Relevance"` を即返す形に置き換える。従来の解決順（前回値 → 保存値／登録既定 → フォールバック）はすべてバイパスされ、全消費経路（classes19 のリポジトリ初期化シードと UI 状態初期化シード）が単一の `getEnumDefault` 経由で常時 Relevance を得る。**拡張ソースの変更であり、.mpp パッチとして表現できる**
3. **保存値のワンショット反転（classes21、拡張側・新規クラス）**: 起動時に一度だけ、保存済みの `newx.timeline.default_reply_sorting` を `"Relevance"` に書き換える同期クラス（jp21 の AppearanceSync と同型。マーカーで再実行を防ぎ、全体を try/catch で包む fail-open）を追加し、`NewXSettingsHost.install()` の末尾から呼ぶ。実効値は 2 で保証されるため、これは Piko設定の表示整合のためのもの。**拡張ソースの変更であり、.mpp パッチとして表現できる**

手ビルドの実物は `src/ReplySortingSync.smali`（同期クラス全文）と `src/ReplySortingResolver-getDefault.smali`（無条件化したメソッドの抜粋）。手ビルドでの差分は classes19 + classes21。

### B16. 引用数ピボット（jp23、classes6 / classes7）

他者の投稿で引用があるとき、詳細ヘッダ行のピボットを「N件の引用を表示」にする（[SPEC.md](SPEC.md) §17）。引用数のデータ自体はサーバー応答に含まれており、不足しているのは搬送と表示だけ。

1. **マッパへの Quote エントリ追加（classes6、`com/x/mappers/b;->e`）**: 応答モデル（postdetail のフラグメントモデル）の **quote_count フィールド（public final）を iget で直接読む**（アクセサは存在しない）。既存の件数付きエントリと同じ引数形でカウントエントリ（種別 Quote、件数、false, 4）を構築し、エントリ配列の**7番目**に追加する（配列生成の範囲を6要素分から7要素分へ拡張）。下流の搬送（エントリ列 → 会話マッパの汎用通過 → 焦点状態）は無変更で届く。**X 本体 dex への後処理**（バンドルでは表現できない）
2. **件数の取得とラベル差し替え（classes7、焦点状態の組み立て `a0->d` の非描画な前計算部）**:
   - 既存のリツイート数取得ループの直後に、同じエントリ列を走査して Quote エントリの件数を保持する第2ループを追加する。**`.locals` は増やさない**こと（引数レジスタの位置がずれて既存の命令形式が壊れる）。スクラッチに使うレジスタは、後段が依存するゼロ定数ペアを全出口で復元すること
   - 他者分岐のピボットラベルは、引用数 > 0 のとき文字列テンプレート `quote_tweets_with_count`（`{count}件の引用`）に件数を埋め込み「を表示」を連結したものに差し替える。0 件では従来の「引用を表示」、自分の投稿は「アクティビティを表示」のまま
   - ピボットの表示条件（showPivot）に **引用数 > 0** を OR で追加する（RT 0 件でも引用があれば出る）。行全体のゲート（返信数 > 1）には触れない

### B17. 詳細ヘッダ行のソートタブ除去（jp23 / jp23.1、classes7）— ⚠ 上級・リスクあり（任意）

詳細ヘッダ行の左側のソートタブだけを消す手順。**この手順は [SPEC.md](SPEC.md) §11 の安全原則に対する条件付き例外（カテゴリ (f)）であり、必須の検証をすべて満たせない場合は実施してはいけない**。省略した場合の形（タブは見えるが §16 のピン留めで実質無力）は jp22 形として仕様どおり成立する — それがフォールバックである。

対象は行の描画メソッド（`com/x/ui/common/media/attribution/e->d`）。構造: 外側の Row の中に、ソートボタンが**入れ子の Row ノード**としてインライン排出され、その後にピボットの置換グループが続く。ボタンは独立した composable 呼び出しではないため、呼び出し箇所の除去はできない。

**方式（goto スキップ）**:

1. ボタンの開始ノード呼び出し（startNode）の**直前に無条件 `goto`** を1命令挿入し、着地点ラベルをボタンの終了ノード呼び出し（endNode）の**直後**（ピボットのゲートの直前）に置く。消えるのはボタンのノード排出だけ。remember 群（シート開閉状態・ソートモード状態・クリックラムダ）と Modifier・測定ポリシーの計算は従来どおり実行され、スロット位置は変わらない

**⚠ 退避コピーの罠（jp23 初版の事故原因）**:

ボタンの排出区間の中では、ボタンの Text 呼び出しの引数シャッフルで低位レジスタが潰れるため、**後段（ピボット／シート）が必要とする値を高位レジスタへ逃がす退避コピー**が行われている。goto で区間を飛ばすと、この退避ごと消えて着地後のレジスタが未定義・型違いになり、**ART ベリファイアがクラスを拒否してアプリが起動不能になる**（jp23 初版の実例: `VerifyError … [0x247] copy-reference v8<-v44 type=Undefined`。検証はリンク時に走るため、詳細画面を開く前に起動時点で落ちる）。

**必須の対策 — 生存レジスタの再現（jp23.1 の形）**:

goto の**直前**に、退避コピーが作るはずだった値を**原本とまったく同じオブジェクト／値**で再現する命令列を置く。以下は本 dex（12.41 の当該メソッド）での実測対応表（12命令、純粋な move / const / sget のみ。composer は呼ばない）:

| # | 命令 | 再現する値 |
|---|---|---|
| 1 | `move-object/from16 v34, v19` | シート開閉状態（MutableState） |
| 2 | `move-object/from16 v36, v10` | ソートモード状態（MutableState） |
| 3 | `move-object/from16 v44, v12` | Composer の Empty センチネル |
| 4 | `move-object/from16 v45, v20` | 配置（アラインメント）単体 |
| 5 | `move-object/from16 v46, v11` | レイアウトポリシー単体 |
| 6 | `move-object/from16 v47, v14` | ノード用 Function0 単体 |
| 7 | `move-object/from16 v48, v15` | レイアウトポリシー単体 |
| 8 | `move-object/from16 v18, v11` | レイアウトポリシー単体（Text 引数スロット用、#5 と同源） |
| 9 | `sget-object v16, Landroidx/compose/ui/text/font/s;->m` | フォント単体（原本は区間内でロードするため再取得） |
| 10 | `const/4 v6, 0x0` | 原本の着地値 int 0 |
| 11 | `const/4 v7, 0x1` | 原本の着地値 true |
| 12 | `move-object/from16 v19, v33` | 行の Modifier。**必ず最後**（#1 の退避元が v19 のため、先に潰すと状態が壊れる） |

順序制約は #1 が #12 より前であることの1点のみ。ベースの X や対象メソッドが変わればレジスタ対応は変わるため、その場合は到達定義解析で対応表を作り直すこと（上表を鵜呑みにしない）。

**必須の検証（納品条件）**:

1. **レジスタ生存性パス**: 最終 APK から抽出した dex に対し、バイパス区間内で書かれる全レジスタについて「着地時点の到達定義」を機械解析し、着地後に読まれるものがすべて正しい型カテゴリで定義済みであること、センチネル・状態クラスのレジスタについては**値の出所（同一の sget 先・同一の退避元）まで原本と一致**することを確認する。**違反ゼロが合格条件**
2. グループ収支（構造呼び出しの開始／終了カウント一致）は**必要条件にすぎない**。jp23 初版はこの確認を通過しながら VerifyError で落ちた — 合格の本体は 1 の生存性である
3. 実機でのコールド起動確認（起動ログに `Verifier rejected class` が無いこと）と、詳細画面の開閉でクラッシュしないこと

手ビルドの実物抜粋は `src/HeaderRowSnippets.smali`（goto＋再現命令列と、B16 の前計算部抜粋）。

### B18. ゾンビフィルター（jp24 / jp24.2、拡張ソース＋トランスプラント）

1. 拡張ソース（piko-newx の extensions/newx）に ZombieFilter / ZombieSettings / ZombieSettingsSection を新規実装し、NewXTimelineFilter（filterItem への評価段追加）/ TimelineModelAccess / FilteredRepliesStore / PostFilterFragment を変更する。実物は [src/ZombieFilter.java](src/ZombieFilter.java) / [src/ZombieSettings.java](src/ZombieSettings.java)、仕様は [SPEC.md](SPEC.md) §18
2. 純ロジックは JVM ハーネスで単体テストする（jp24.2 時点で 147 件）
3. javac → D8（min-api 26）→ baksmali で smali 化し、配置する。**classes19 の NewXTimelineFilter 本体にはパッチ注入ブリッジがあるため、変更/新規メソッドのみを明示リスト（TAKE 方式）でトランスプラントし、本体バイト列へ一括差分を当てない**。classes21 はファミリー単位で差し替える（プレフィックス境界に注意 — PostFilterRule と PostFilterRuleAdapter のような前方一致の巻き込みを防ぐこと）
4. 互換性のため、旧シグネチャ互換コンストラクタ（Evaluation / MemoEntry）とマーカー型クラス（-IA）を保持する
5. 全クラス相互参照監査で、ベースライン比の新規欠落ゼロを確認する

### B19. データ使用量デフォルト（jp24.2、classes21）

1. 新規クラス `JpDefaults`（[src/JpDefaults.java](src/JpDefaults.java)）を classes21 に追加する
2. `NewXTimelineFilter.filterTimelineItems` の入口へ JpDefaults の呼び出しをトランスプラントする（B18 と同じ TAKE 方式）
3. JpDefaults はマーカー `newx.jp.data_usage_defaults_v1` が無いときだけ、本体 SharedPreferences へ video_autoplay="never" / image_quality="wifi_and_mobile" / video_quality="wifi_and_mobile" を書く

### B20. ダウンロード保存先デフォルト（jp24.3 / jp24.5、classes19 / classes21）

1. 新規クラス `JpMediaStoreDestination` / `JpRoutingDestination`（[src/](src/) の同名 .java）を classes21 に追加する
2. `NewXDownloadFolders` に defaultTreeUri / effectiveTreeUri（Context 版・引数なし版）を追加し、`NewXDownloader.install()` の writer を JpRoutingDestination に差し替え、`NewXDownloadTransfer` の complete() 公開を有効化する（抜粋は [src/DownloadSnippets.smali](src/DownloadSnippets.smali) 断片1〜3）
3. ゲート変更: NativeDownloadRouter のフォールバック条件を UNUSABLE のみにし、enqueue は effectiveTreeUri を使う
4. **classes19 の InlineDownloadButton 本体は出荷実物を保持**し、次の2点だけを smali 編集する: (a) needsFolderPrompt の本体を UNUSABLE 等値判定に差し替え（断片4）、(b) enqueueDownload 内の treeUri 呼び出し（1箇所）を effectiveTreeUri(MediaKind) 宛に変更（断片5）
5. ⚠ **本体の丸ごと再コンパイルは厳禁**。本体にはパッチ注入ブリッジ（createDownloadAction / isCompositionDownloadableMedia）が同居し、再コンパイルすると実処理がダミーに戻ってダウンロードボタンが消失する（jp24.3 で発生、jp24.5 で修正）。作業前にブリッジ走査を行い、差し替え後は2メソッドの実処理が残っていることを確認する

### B21. 引用行の常時表示（jp24.4、classes7）

1. プレゼンタ `Lcom/x/urt/items/post/h1;->b` の返信数ゲート false 側（:cond_465）の `const/16 v36, 0x0` を `0x1` に変える（1行、命令サイズ不変。抜粋は [src/QuoteRowSnippets.smali](src/QuoteRowSnippets.smali)）
2. v36 が FocalPostState の h 引数（showReplySorter）へそのまま流れ、他に再代入が無いことを確認する。同メソッド末尾のプレースホルダ全ゼロ初期化は対象外

### B22. ナビバーの DM アイコン（jp24.6、classes18 / リソース）

1. 未選択時: アイコンレジストリ `Lcom/x/icons/a;`（classes18）の clinit で、フィールド s1 に入る drawable id を 0x7f0804c7（ic_vector_chat_stroke）から **0x7f080546（ic_vector_email_stroke）** へ変える（1行。抜粋は [src/DmIconSnippets.smali](src/DmIconSnippets.smali)）
2. 選択時: `res/drawable/ic_vector_chat.xml` の pathData を自作の塗り封筒（[src/DmIconEnvelope.xml](src/DmIconEnvelope.xml)）に差し替える。AXML の編集は文字列プールの再構築による外科編集で行い、他チャンクはバイト不変に保つ。編集後は aapt2 dump xmltree で新しい pathData が読めることを確認する
3. 封筒ベクターは事前に描画して形を確認する（外形が email_stroke と揃っていること、フラップの V 字グルーヴが抜けていること）

## 手順 C — 再署名

1. `zipalign -p 4` で整列
2. `apksigner` で**自分の署名鍵**により署名する（スキームは v3 のみでよい。対象は API 29+）
3. 検証: `apksigner verify`、`aapt2 dump badging` でパッケージ名 `com.twitter.android.piko` / versionCode `312410001` / ラベル `Twitter` を確認

## 検証の作法（毎回行うこと）

- 施工前の dex は、無変更で再構築するとバイト一致すること（ラウンドトリップ再現性）を先に確認してから編集する。再現しない dex（ツールチェーン製法が違うもの）は全面再構築を避け、B11 のような最小バイナリパッチに切り替える
- **ブリッジ走査（jp24.5 で必須化）**: 拡張クラスの本体を差し替える前に、出荷バイナリで「実処理＋ `unpatchedBridge` の throw」を両方含むメソッドを洗い出す。該当クラスはメソッド単位のトランスプラントか smali 直接編集のみで触り、差し替え後にブリッジの実処理が残っていることを確認する。メソッド集合・署名・アクセスフラグの一致だけでは不十分
- 分岐や goto を含むメソッドを編集したら、レジスタ生存性スキャンを行い、合流点で未定義のレジスタが無いことを確認する（B17 の教訓）
- ファミリー差し替え後は、全クラス相互参照監査でベースライン比の新規欠落ゼロを確認する
- 各段階で、前段の APK との zip エントリ差分（CRC 比較）が「意図したエントリだけ」であることを assert する
- 最終 APK から編集対象 dex を再展開し、意図した変更だけが入っていることを再確認する

---

*本書は公開用の手順書です。数値・対象の正は [SPEC.md](SPEC.md)。版ごとの経緯は [CHANGELOG.md](CHANGELOG.md)。*
