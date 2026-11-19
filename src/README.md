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

## ZombieFilter.java / ZombieSettings.java — jp24 ゾンビフィルター

jp24 のゾンビフィルター（[../SPEC.md](../SPEC.md) §18）の評価器と設定キーの実物です。配置先は **classes21.dex**、`Lapp/morphe/extension/newx/timeline/` 配下。

- **ZombieFilter.java**: ルール評価器の全文（Z1 文字種 / Z2 絵文字のみ / Z4a 完全重複 / Z4b 近似重複（jp24.2）/ Z7 コピー＋リンク・引用 / Z8 連投 / Z9 引用テンプレ使い回し / Z10 短文＋引用 / Z11' 外国語ベイト（jp24.8）の判定 `isForeignBait` と、指定アカウント用の理由定数 BLOCKED_AUTHOR（jp24.11）を含む）。入口は `NewXTimelineFilter.filterItem()` に評価段を1段追加する形で、変更した本体・UI 側（NewXTimelineFilter / TimelineModelAccess / FilteredRepliesStore / PostFilterFragment / ZombieSettingsSection 等）の全文は同梱しません — バンドル側の piko-newx ソースに同名ファイルがあるため、差分は [../BUILD.md](../BUILD.md) B18 の要点と本ファイルで追えます
- **ZombieSettings.java**: 設定キー（`newx.content.zombie_filtering.*`）と既定値の定義（jp24.8 で foreign_bait、jp24.10 で double_reply、jp24.11 で blocked_authors を追加）
- 純ロジックの単体テストは JVM ハーネスで実施（jp24.2 時点で 147 件全通過）

## JpDefaults.java — jp24.2 データ使用量デフォルト

初回だけ本体設定のデフォルトを書き換える小さなクラス（[../SPEC.md](../SPEC.md) §19、[../BUILD.md](../BUILD.md) B19）。配置先は **classes21.dex**、`Lapp/morphe/extension/newx/timeline/JpDefaults;`。`NewXTimelineFilter.filterTimelineItems` の入口から一度だけ呼ばれ、本体 SharedPreferences に video_autoplay="never" / image_quality="wifi_and_mobile" / video_quality="wifi_and_mobile" を書く（マーカー `newx.jp.data_usage_defaults_v1`）。固定化ではなくデフォルト変更なので、UI からの変更は以後も有効です。

## JpMediaStoreDestination.java / JpRoutingDestination.java / DownloadSnippets.smali — jp24.3 / jp24.5 保存先デフォルト

ダウンロードの保存先デフォルト（[../SPEC.md](../SPEC.md) §20、[../BUILD.md](../BUILD.md) B20）の実物です。配置先は **classes21.dex**、`Lapp/morphe/extension/newx/misc/` 配下。

- **JpMediaStoreDestination.java**: MediaStore コレクション（画像=Pictures/Twitter、動画=Movies/Twitter）への宛先バックエンド。crimera ダウンロードエンジンの `DestinationWriter` 実装で、IS_PENDING で insert → 転送 → complete() で公開する
- **JpRoutingDestination.java**: 宛先 Uri が MediaStore コレクションなら MediaStore 側、それ以外は従来の SAF 側へ振り分けるルーター
- **DownloadSnippets.smali**: NewXDownloadFolders の追加メソッド（defaultTreeUri / effectiveTreeUri 2種）と、InlineDownloadButton 側の外科編集2点（needsFolderPrompt 本体差し替え、enqueueDownload の呼び出し差し替え）の抜粋。⚠ InlineDownloadButton 本体にはパッチ注入ブリッジがあるため丸ごと差し替え厳禁 — jp24.3 で実際にボタンを消した事故と対策（ブリッジ走査）は B20 と [../CHANGELOG.md](../CHANGELOG.md) の jp24.5 項を参照

## QuoteRowSnippets.smali — jp24.4 引用行の常時表示

詳細ヘッダ行の表示ゲート（返信数 > 1）を常時表示にする1行変更（[../SPEC.md](../SPEC.md) §21、[../BUILD.md](../BUILD.md) B21）。対象は X 本体側（classes7）のプレゼンタのため、変更箇所の説明と前後値のみを収録しています。

## DmIconSnippets.smali / DmIconEnvelope.xml — jp24.6 DM アイコン

ナビバーのチャットアイコンを従来の DM マーク（封筒）にする変更（[../SPEC.md](../SPEC.md) §22、[../BUILD.md](../BUILD.md) B22）。

- **DmIconSnippets.smali**: 未選択時 = アイコンレジストリ（classes18）の付け替え1行、選択時 = ic_vector_chat.xml の pathData 差し替え、の2点の抜粋
- **DmIconEnvelope.xml**: 選択時用の自作「塗りの封筒」ベクターの XML ソース。未選択時の封筒輪郭（ic_vector_email_stroke、アプリ内蔵）と外形を揃え、フラップの V 字を逆巻きサブパスで抜いたもの

## JpForeignBaitCheck.java — jp24.8 / jp24.9 Z11' 外国語ベイト

Z11'（日本語を含まない外国語返信）のグルー（[../SPEC.md](../SPEC.md) §18、[../BUILD.md](../BUILD.md) B24）。配置先は **classes21.dex**、`Lapp/morphe/extension/newx/timeline/JpForeignBaitCheck;`。判定本体は ZombieFilter.isForeignBait（ルート本文が日本語を含む／返信本文と表示名のどちらにも日本語が無い／返信に文字が含まれる、の3条件。材料が欠ける場合はフェイルオープン）。評価器への挿入は classes19 側の外科編集で、jp24.8 では挿入レジスタの型競合でクラッシュし jp24.9 で修正した経緯があります（レジスタ型ゲート必須 — BUILD.md の「検証の作法」参照）。

## JpDoubleReplyCheck.java — jp24.10 Z12 同じ投稿への2回目のリプライ

Z12 のグルー（[../SPEC.md](../SPEC.md) §18、[../BUILD.md](../BUILD.md) B25）。配置先は **classes21.dex**、`Lapp/morphe/extension/newx/timeline/JpDoubleReplyCheck;`。会話ルートごとに表示順のノートログ {postId, authorId, parentId} を保持し（再ノート時はその地点まで切り詰める Z8 と同じ再生意味論）、同一著者の同一親投稿への直接リプライが2回目以降なら DOUBLE_REPLY を返す。ルート投稿へのリプライ（Z8 の領域）・自分の投稿へのリプライ（セルフチェーン）・親著者不明時は対象外（フェイルオープン）。

## BlockedAuthorsStore.java — jp24.11 / jp24.12 / jp24.13.17 指定アカウント非表示

指定アカウントの著者 ID リストの保存層（[../SPEC.md](../SPEC.md) §24、[../BUILD.md](../BUILD.md) B26）。配置先は **classes21.dex**、`Lapp/morphe/extension/newx/postfilter/BlockedAuthorsStore;`。照合は数値 ID のみ（ハンドル名は変わり得るため使わない）。同梱定数 → 永続セットへの初回だけのシードと、ユーザー編集は永続層のみという二層構造です。

jp24.13.17 で設定画面の ID エディタ（[../SPEC.md](../SPEC.md) §41、[../BUILD.md](../BUILD.md) B43）向けに `normalizeIdInput` / `idInputError`（数値のみ・19桁まで・先行ゼロ正規化・全ゼロ不可）と、JSON バンドル取り込み用の `replaceAll`（SEEDED フラグを必ず立てるため、初回起動前でも同梱シードが混入しない）が加わりました。

⚠ **本リポジトリ同梱版のシードは空です**（作者個人の対象リストは配布しません）。自分のビルドでは `defaultIds()` に隠したいアカウントの数値 ID を自分で追加してください。判定・記録側（NewXTimelineFilter への外科挿入、理由 BLOCKED_AUTHOR、ホワイトリストより優先、会話ルート免除）は BUILD.md B26 が正です。

## MuteCategory.java / MuteCategoryStore.java — jp24.13 カテゴリ別ミュート

カテゴリ別ミュート（[../SPEC.md](../SPEC.md) §25、[../BUILD.md](../BUILD.md) B27）のデータクラスと保存層の全文。配置先は **classes21.dex**、`Lapp/morphe/extension/newx/postfilter/` 配下。

- **MuteCategory.java**: カテゴリ1件分の定義（有効フラグ、対象フィールド4種＝本文／引用／@ID／表示名、晒し検出サブトグル、単語リスト）
- **MuteCategoryStore.java**: プリセットのシード定義そのものを含む保存層。二層構造（同梱シード → 永続 JSON `newx.content.mute_categories`）、NFKC＋小文字化の部分文字列マッチで一致語を返し、理由 `MUTE_CAT_<ID>:<一致語>` の記録に使う。晒し系にはコード固定の個人情報パターン検出（電話番号形式／〒郵便番号／住所ヒューリスティクス）がサブトグル付きで入っています
  - プリセットは jp24.13 時点の5カテゴリ（ニュース / 政治系 / 荒らし / 晒し系 / 炎上系）から、jp24.13.12 で ジェンダー・男女論 / 水商売 / 日韓・日中対立（[../SPEC.md](../SPEC.md) §36、[../BUILD.md](../BUILD.md) B38）、jp24.13.13 で 宗教・移民 と既存プリセットの多言語種拡張（§37、B39）、jp24.13.14 で 地域・原発（§38、B40）、jp24.13.15 で ジェンダー種に「男女分断」（§39、B41）、jp24.13.16 で 誘導・釣り（§40、B42）が加わり、**全11カテゴリ**になりました。追加は append-missing 移行（ユーザーが編集したリストは一切触らない）で、種拡張は「旧種と完全一致する場合のみ置き換える」ルールです
  - jp24.13.16 で照合対象に**展開URL**（本文・引用の expandedUrl / displayUrl）が加わりました（`Snapshot.findMatch` の6引数版。本文テキストには t.co しか入っていないため、リンク先ドメインでの照合は URL エンティティ経由でしか成立しません）。晒し検出器は URL には適用しません
- 設定 UI のセクション（MuteCategorySettingsSection）と判定の組み込み（NewXTimelineFilter / FilterConfig / FilteredRepliesDialog の変更）は同梱しません — 手順と要点は BUILD.md B27 を参照

## BannerUi.java / SentBanner.smali / SentBanner$k.smali / SentBanner$Fin.smali — jp24.13.5 送信完了バナー

送信完了表示のクラシック化（[../SPEC.md](../SPEC.md) §29、[../BUILD.md](../BUILD.md) B31）の実物です。配置先は **classes6.dex**、パッケージ `piko/jp135`。

- **BannerUi.java**: 表示実体。Activity の decorView に載せる通常の View（LinearLayout）で、淡青背景 `#E8F5FE`・角丸 14dp、コード描画の Drawable（塗り円 `#1DA1F2` ＋白チェック）、文字 `#14171A` 15sp、ボタンなし。表示 3,000ms、出現フェード＋スライド 220ms。全エントリポイントがフェイルセーフ（失敗時は完了コールバックだけを返す）で、コールバックは必ずメインスレッド投稿経由で非同期に届く。コンパイルは Arc と同じ作法（`javac -source 8 -target 8` → `d8 --min-api 29` → smali 化）
- **SentBanner.smali**: 提示役 `com/x/composer/upload/v2/notifications/n` のネイティブ提示と**同一の suspend 契約**を実装する手書きグルー。Activity（`attach` で WeakReference 保持。供給は jp15 の `Arc.attach` への転送1命令）と表示文言が取れれば BannerUi で表示し、いずれか欠ければ**ネイティブ提示 `n.e` に委譲**する。表示中の追加送信は即 AutoDismiss で完了
- **SentBanner$k.smali**: 上記の継続状態マシン（ContinuationImpl）。**SentBanner$Fin.smali**: 終了コード（タップ = MessageClick／その他 = AutoDismiss）の受け渡し用ホルダ
- フックは2点のみ: 提示役 `n.c` 内の提示呼び出し1命令の差し替えと、`Arc.attach` への転送追加（手順は BUILD.md B31）。結果処理は提示役本来の分岐が担うため、タップ時の詳細遷移はネイティブと同一です

## RecommendKeepGate.java / RecommendKeepStore.java / RecommendKeepSettingsSection.java / CurrentViewer.java — jp24.13.9〜13.23 おすすめキープフィルター

フォロー外のおすすめ投稿を「興味カテゴリ（技術系 / 動物）に合うものだけ残す」キープフィルター（[../SPEC.md](../SPEC.md) §33、[../BUILD.md](../BUILD.md) B35）の拡張側クラスの全文。配置先は **classes21.dex**。

- **RecommendKeepGate.java**（`timeline/`）: 判定役。対象タイプは FOR_YOU / EXPLORE_FOR_YOU のみ。フォロー中応答から著者 ID を収穫してキャッシュし（上限 5,000 件、期限なし）、分類は「友情フラグ OR キャッシュ」の OR 意味論。jp24.13.18 の診断修正（§42、B44）後は「著者 ID が収穫できたこと」自体を診断のヒットとし、jp24.13.19（§43、B45）ではページ単位プリスキャン `hasFollowedSignal` が「フォロー済み著者が1人もいないページは丸ごと素通し（記録も書かない）」を担います。jp24.13.21（§45、B47）でページ収量ガード（8件以上・保持3件以下で素通し）、jp24.13.23（§49、B51）で比率ガード（保持が半分未満なら素通し。従来条件は算術上これに含まれる）が加わりました
- **RecommendKeepStore.java**（`postfilter/`）: 保存層（永続 JSON `newx.content.recommend_keep`）。語句マッチは jp24.13.11 以降、純 ASCII 語句に**単語境界**を要求します（§35、B37。「AI」は種からも削除）。診断カウンタの更新は `recordHarvestSamples` に集約され、TRUSTED / DEAD 到達後は単調（巻き戻しなし）。jp24.13.17 では JSON バンドル用の投影エクスポート `exportProjection()`（カテゴリ・オプションのみ。**フォローキャッシュと診断状態は含めない**）が加わりました。**jp24.13.20（§44、B46）で schema 4** となり、フォローキャッシュ・診断はアカウント別パーティション（`accounts: {userId: …}`、LRU 上限 8）に分離されました。移行は旧和集合データの**破棄**です（帰属記録が無いため種として入れない）
- **CurrentViewer.java**（`timeline/`）: 表示中アカウントの ID を保持する小さなホルダ（jp24.13.20 で新設）。本体側ブリッジからの通知で更新され、フィルタはパス開始時に1回だけキーを解決します。未判定（0）の間はゲート完全 OFF・収穫なしが仕様です
- **RecommendKeepSettingsSection.java**（`postfilter/`）: 設定セクション（マスター、カテゴリ別 ON/OFF と語句編集、診断状態の表示とリセット）。jp24.13.20 以降、診断表示・リセットは現在のアカウントのパーティションが対象で、アカウント ID を併記します。単語エディタは BulkPhraseInput（下記）経由の一括追加に対応しています
- 判定の入口（classes19 `NewXTimelineFilter.filterExploreItems` への外科挿入、著者までの5段ナビゲーション用ブリッジ `TimelineModelAccess` の追加分、閲覧者ブリッジの本体側挿入）は同梱しません — 手順と要点は BUILD.md B35 / B46 を参照

## PostFilterFragment.java / BulkPhraseInput.java — jp24.13.21 / jp24.13.22.2 ツイートフィルター画面

「ツイートフィルター」画面本体（`postfilter/`、classes21.dex）の全文と、単語の一括追加ユーティリティです。

- **PostFilterFragment.java**: jp24.13.3 の ScrollView 化に続き、jp24.13.21（§45、B47）でルール一覧の固定高さ ListView を廃して ScrollView 内の行描画に統一しました（画面全体が1本のスクロール面）。jp24.13.22.2（§48、B50）では、アダプタへのデータ投入（`submit`）欠落でキーワードルールがあると画面が落ちた事故を、アダプタを唯一の真実とする形で修正しています — 件数と行の出所を二重管理しないことが要点です
- **BulkPhraseInput.java**: 単語追加欄の入力を `[,、，\r\n]+` で分割し、全角スペース含む trim と初出順の重複排除を行う純粋ユーティリティ。カテゴリ別ミュートとおすすめキープの単語エディタから使います

## SavedMediaFragment.java / SavedMediaStore.java / SavedMediaSaf.java / SavedMediaFolders.java / SavedMediaSettings.java — jp24.13.22〜13.24 保存メディアビューア

ダウンロード済みメディアの認証付きビューア（[../SPEC.md](../SPEC.md) §46、[../BUILD.md](../BUILD.md) B48）の拡張側クラス群の全文。配置先は **classes21.dex**、`misc/` 配下。

- **SavedMediaFragment.java**: 画面本体。開くたびにフレームワークの生体認証（実在する `BiometricPrompt.Builder` 経由。jp24.13.22.2 で AndroidX 形状の誤用を修正 — §48、B50）が必須で、認証前にサムネイル・一覧は一切描きません。生体認証も画面ロックも無い端末では開きません（fail-closed）。列挙は jp24.13.23 で3段の MediaStore 照会に段階化（§49、B51）、jp24.13.24 で **SAF 主軸**（§50、B52）になりました: SAF → MediaStore 段階列挙 → 拡張インデックスの順にマージし、重複排除は URI 一致＋ (表示名, サイズ) キーの先勝ちです。ビューア経路からメディアスキャンは誘発せず、SAF ファイルの MediaStore 行も作りません（`.nomedia` の隠蔽効果を維持するため）
- **SavedMediaStore.java**: ダウンロード完了時に (Uri, ファイル名, MIME) を記録する拡張側インデックス（MediaStore に行が無いファイルの補完）
- **SavedMediaSaf.java**: `DocumentsContract` の子カーソルによるツリー列挙（再帰深さ4まで、ドットファイル・0バイト・未知 MIME を除外、ツリーごとに失敗を隔離）と重複排除キーのユーティリティ
- **SavedMediaFolders.java / SavedMediaSettings.java**: ビューア専用のフォルダ許可（`newx.content.saved_media.*`）の保存層と状態判定（未許可／失効／正常）。ダウンロード先フォルダの許可は読み取り再利用します。jp24.13.24.3（§53、B55）で、保存呼び出しが実在する基底クラス API の形であることを連携ゲートで担保しています

## ConflictResolver.java — jp24.13.22 重複時のダウンロード前確認

同名ファイルの重複時確認（[../SPEC.md](../SPEC.md) §46、[../BUILD.md](../BUILD.md) B48）の全文。配置先は **classes21.dex**、`misc/` 配下。設定トグル（既定 ON）→ 存在チェック（MediaStore 照会／SAF 子ドキュメント列挙、例外時は「なし」扱い）→ Activity があればダイアログ（上書き／別名で保存／キャンセル、外側タップと dismiss はキャンセル扱い、ラッチ待ち上限つき）、無ければ設定ポリシーで続行、という解決役です。両ダウンロードファネルへの組み込み（enqueue 直前の挿入とリクエストの作り替え）は同梱しません — 手順は B48 を参照

## FolderPicker.java / FolderPickerActivity.java — jp24.13.24.1 / jp24.13.24.3 フォルダ許可の永続化

フォルダピッカー（crimera ダウンローダ側、classes20.dex）の jp24.13.24.1 以降の形です（[../SPEC.md](../SPEC.md) §51、[../BUILD.md](../BUILD.md) B53）。

- 起動 Intent に保存先の設定キーを載せ、ピッカー結果の受け取り直後に **Activity 内で** 権限取得＋レジストリ書き込みを行ってから、静的コールバックを再読み込みの合図として発火します。プロセスがピッカー表示中に再生成されても Intent が生き延びるため、許可が無言で消えません。extras の無い従来の呼び出し（ダウンロード先フォルダ用）は出荷経路のままです
- jp24.13.24.3（§53、B55）: 保存呼び出しは、出荷 dex に実在する基底クラス `Setting.save(Object)` の形です。コンパイル用スタブが実在しないオーバーロードを宣言していたことが許可喪失の真因でした — スタブとハーネスのフェイクは出荷形状に合わせることが再発防止の要点です

## TypeaheadAuthorFilter.java — jp24.13.15 検索候補の指定アカウント非表示

検索候補（タイプアヘッド）から指定アカウントを除去するヘルパー（[../SPEC.md](../SPEC.md) §39、[../BUILD.md](../BUILD.md) B41）の全文。配置先は **classes21.dex**、`Lapp/morphe/extension/newx/postfilter/TypeaheadAuthorFilter;`。判定材料は既存のものだけ（トグル `newx.content.zombie_filtering.blocked_authors` と `BlockedAuthorsStore` のスナップショット）で、新規設定・新規保存領域はありません。フックは X 本体側 classes6 のタイプアヘッド集約リポジトリの成功出口4点への2命令挿入（手順は B41）で、対象は検索サジェスト・@メンション補完・DM 宛先検索・支払いピッカー等の**全候補面**です（ハッシュタグ候補は無改変）。全体が try/catch でフェイルオープンし、該当なし・トグル OFF のときは入力と同一の List オブジェクトを返します。候補の非表示は記録しません。

## FilterBundleImportExport.java — jp24.13.17 フィルター設定の JSON 入出力

ツイートフィルター設定の JSON バンドル（`{"format":"comebacktwitter-filter-bundle","version":1}`）入出力（[../SPEC.md](../SPEC.md) §41、[../BUILD.md](../BUILD.md) B43）の全文。配置先は **classes21.dex**、`Lapp/morphe/extension/newx/postfilter/FilterBundleImportExport;`。

- エクスポート対象は muteCategories / keywordRules / recommendKeep（投影のみ）の3セクション。**blockedAuthors はエクスポートに決して含めません**（プライバシー上の決定。インポート側は手書きファイルの blockedAuthors セクションを受け付け、無効エントリは件数報告付きでスキップします）
- インポートは2段階（全セクション検証 → 有効なものだけ適用）。muteCategories は全置換＋欠けたプリセットの既定値補完で、検証時に `preset` フラグと晒し検出器の付け替え（なりすまし）を強制補正します
- ファイル／クリップボードの両経路、結果ダイアログ、画面側の配線（ボタン配置・onActivityResult チェーン・onResume 再構築）は同梱しません — 手順は BUILD.md B43 を参照

## 同梱していないもの

- **更新音ヘルパー（`Lpiko/jp8/Snd;`）**: 音源（Twitter 8.65 の `psst2.ogg` / `pop.ogg`）をバイト配列として埋め込む設計のため、音源の権利の関係でソース・バイナリとも同梱しません。再現方法は [../BUILD.md](../BUILD.md) の手順 B9 を参照してください（構造は単純で、SoundPool で2音を状態エッジで鳴らすだけです）
