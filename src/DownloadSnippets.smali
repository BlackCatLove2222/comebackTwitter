# DownloadSnippets.smali — ダウンロード保存先デフォルト（jp24.3 / jp24.5）の実物抜粋集
#
# 対象は拡張側クラス（app.morphe.extension.newx.misc）。全文ではなく、jp24.3 で
# 追加・変更したメソッドと、jp24.5 で外科的に再適用した箇所を収録する。
# 手順の全体は ../BUILD.md の B20 を参照。
#
# ⚠ InlineDownloadButton 本体（classes19）は「丸ごと再コンパイル差し替え」厳禁。
# 本体にはパッチ注入ブリッジ（createDownloadAction /
# isCompositionDownloadableMedia — ソース上は unpatchedBridge を投げるダミーで、
# 出荷バイナリでは実処理が注入済み）が同居する。jp24.3 の初版はこれを潰して
# ダウンロードボタンが消失した（jp24.5 で修正）。本体は出荷実物を保持し、
# 下記2点だけを smali 編集で適用すること。差し替え前に「実処理＋ブリッジ throw を
# 両方含むメソッド」の走査（ブリッジ走査）を必ず行うこと（BUILD.md「検証の作法」）。

# ---------------------------------------------------------------------------
# 断片1（B20）: NewXDownloadFolders.defaultTreeUri（jp24.3 新規、classes21）
#   未設定時のデフォルト保存先 = MediaStore コレクション Uri を返す。
# ---------------------------------------------------------------------------
.method public static defaultTreeUri(Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;)Landroid/net/Uri;
    .registers 2

    .line 60
    sget-object v0, Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;->VIDEOS:Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;

    if-ne p0, v0, :cond_7

    .line 61
    sget-object p0, Landroid/provider/MediaStore$Video$Media;->EXTERNAL_CONTENT_URI:Landroid/net/Uri;

    goto :goto_9

    .line 62
    :cond_7
    sget-object p0, Landroid/provider/MediaStore$Images$Media;->EXTERNAL_CONTENT_URI:Landroid/net/Uri;

    .line 60
    :goto_9
    return-object p0
.end method

# ---------------------------------------------------------------------------
# 断片2（B20）: NewXDownloadFolders.effectiveTreeUri(Context, MediaKind)（jp24.3 新規）
#   設定済みフォルダがあればそれ、無ければデフォルト（断片1）を返す。
#   treeUri 本来の意味（設定済みのみを返す）は設定 UI / MediaMerger が使うため不変。
# ---------------------------------------------------------------------------
.method public static effectiveTreeUri(Landroid/content/Context;Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;)Landroid/net/Uri;
    .registers 2

    .line 71
    invoke-static {p0, p1}, Lapp/morphe/extension/newx/misc/NewXDownloadFolders;->isConfigured(Landroid/content/Context;Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;)Z

    move-result p0

    if-eqz p0, :cond_b

    invoke-static {p1}, Lapp/morphe/extension/newx/misc/NewXDownloadFolders;->treeUri(Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;)Landroid/net/Uri;

    move-result-object p0

    goto :goto_f

    :cond_b
    invoke-static {p1}, Lapp/morphe/extension/newx/misc/NewXDownloadFolders;->defaultTreeUri(Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;)Landroid/net/Uri;

    move-result-object p0

    :goto_f
    return-object p0
.end method

# ---------------------------------------------------------------------------
# 断片3（B20 / jp24.5）: NewXDownloadFolders.effectiveTreeUri(MediaKind)（引数なし版）
#   InlineDownloadButton.enqueueDownload は呼び出し時点で Context レジスタが
#   synchronized 用に上書き済みのため、Context なしで解決できる版を別途用意した。
#   UNUSABLE 状態は呼び出し側ゲート（断片4 / NativeDownloadRouter）で先に捌かれ、
#   ここへは到達しない。
# ---------------------------------------------------------------------------
.method public static effectiveTreeUri(Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;)Landroid/net/Uri;
    .registers 2

    invoke-static {p0}, Lapp/morphe/extension/newx/misc/NewXDownloadFolders;->treeUri(Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;)Landroid/net/Uri;

    move-result-object v0

    if-eqz v0, :cond_7

    return-object v0

    :cond_7
    invoke-static {p0}, Lapp/morphe/extension/newx/misc/NewXDownloadFolders;->defaultTreeUri(Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;)Landroid/net/Uri;

    move-result-object v0

    return-object v0
.end method

# ---------------------------------------------------------------------------
# 断片4（B20 / jp24.5）: InlineDownloadButton.needsFolderPrompt の本体差し替え（classes19）
#   原版: 「状態が null でなく、かつ使用不能ならプロンプト」
#     → フォルダ未設定（UNSET）でもプロンプトが出て保存できなかった。
#   差し替え後: UNUSABLE（選択済みフォルダの権限喪失）のときだけプロンプト。
#   UNSET はデフォルト保存（MediaStore）へ流れる。
# ---------------------------------------------------------------------------
.method private static needsFolderPrompt(Lapp/morphe/extension/newx/misc/NewXDownloadFolders$DestinationState;)Z
    .registers 2

    sget-object v0, Lapp/morphe/extension/newx/misc/NewXDownloadFolders$DestinationState;->UNUSABLE:Lapp/morphe/extension/newx/misc/NewXDownloadFolders$DestinationState;

    if-ne p0, v0, :cond_6

    const/4 p0, 0x1

    goto :goto_7

    :cond_6
    const/4 p0, 0x0

    :goto_7
    return p0
.end method

# ---------------------------------------------------------------------------
# 断片5（B20 / jp24.5）: InlineDownloadButton.enqueueDownload の保存先解決（classes19）
#   メソッド内に1箇所だけある treeUri 呼び出しを、断片3のオーバーロード宛に変更。
#
#   変更前:
#     invoke-static {v0}, Lapp/morphe/extension/newx/misc/NewXDownloadFolders;->treeUri(Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;)Landroid/net/Uri;
#   変更後:
#     invoke-static {v0}, Lapp/morphe/extension/newx/misc/NewXDownloadFolders;->effectiveTreeUri(Lapp/morphe/extension/newx/misc/NewXDownloadFolders$MediaKind;)Landroid/net/Uri;
# ---------------------------------------------------------------------------
