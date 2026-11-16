# jp23 / jp23.1 — 詳細ヘッダ行まわりの手ビルド抜粋（X 12.41.0-prod.01 の実ビルドから採取）
#
# これは参照用の抜粋集です。各断片は「追加・変更した命令列」だけを示します。
# 前後の文脈（レジスタの割り当て・ラベル番号）はベースの版に依存するため、
# そのままコピーするのではなく、[../BUILD.md](../BUILD.md) の B16 / B17 の
# 手順と対応表に沿って対象メソッドへ組み込んでください。


# =====================================================================
# 断片1 (B17): ソートタブ除去 — goto スキップ＋生存レジスタ再現
# 配置先: classes7 / Lcom/x/ui/common/media/attribution/e;->d(...)
# 位置: ボタンの開始ノード呼び出し (o0()) の直前。
#   先行する文脈の最後は Modifier 実体化 (ui/a->d の結果が v6) 。
# jp23.1 の納品形。jp23 初版は再現命令列が無く、VerifyError で起動不能になった。
# =====================================================================

    # --- ここから jp23.1 で追加した12命令（生存レジスタの再現） ---
    move-object/from16 v34, v19    # シート開閉状態 (MutableState)

    move-object/from16 v36, v10    # ソートモード状態 (MutableState)

    move-object/from16 v44, v12    # Composer の Empty センチネル

    move-object/from16 v45, v20    # 配置（アラインメント）単体

    move-object/from16 v46, v11    # レイアウトポリシー単体

    move-object/from16 v47, v14    # ノード用 Function0 単体

    move-object/from16 v48, v15    # レイアウトポリシー単体

    move-object/from16 v18, v11    # レイアウトポリシー単体（Text 引数用）

    sget-object v16, Landroidx/compose/ui/text/font/s;->m    # フォント単体（再取得）

    const/4 v6, 0x0    # 原本の着地値 int 0

    const/4 v7, 0x1    # 原本の着地値 true

    move-object/from16 v19, v33    # ★必ず最後: 行の Modifier（v34 の退避元を潰すため）

    # --- jp23 で追加した goto 本体。着地点はボタンの終了ノード呼び出し
    #     (u(true)) の直後、ピボットのゲートの直前 ---
    goto/16 :goto_a

    # （以下、到達不能になったボタンの排出区間が元のまま残る）


# =====================================================================
# 断片2 (B16-1): マッパへの Quote エントリ追加
# 配置先: classes6 / Lcom/x/mappers/b;->e(...)
# 位置: 既存カウント読み取り列の :goto_6 の直後。
# この後、エントリ配列の生成を filled-new-array/range {v19 .. v25} に
# 拡張し（6要素→7要素）、本エントリが7番目に入る。
# =====================================================================

    :goto_6
    iget-object v5, v1, Lcom/x/android/postdetail/fragment/rb;->f:Ljava/lang/Long;

    if-eqz v5, :cond_jp23q

    invoke-virtual {v5}, Ljava/lang/Long;->longValue()J

    move-result-wide v25

    goto :goto_jp23q

    :cond_jp23q
    const-wide/16 v25, 0x0

    :goto_jp23q
    invoke-static/range {v25 .. v26}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;

    move-result-object v27

    new-instance v25, Lcom/x/models/n4;

    sget-object v26, Lcom/x/models/y5;->Quote:Lcom/x/models/y5;

    const/16 v28, 0x0

    const/16 v29, 0x4

    invoke-direct/range {v25 .. v29}, Lcom/x/models/n4;-><init>(Lcom/x/models/y5;Ljava/lang/Long;ZI)V


# =====================================================================
# 断片3 (B16-2): 焦点状態の組み立て — Quote 件数の取得（第2ループ）
# 配置先: classes7 / Lcom/x/urt/items/post/a0;->d(...)
# 位置: 既存のリツイート数ループの直後 (:goto_4 の後段)。
# quoteCount は v10/v11 (wide) に保持する。.locals は増やしていない。
# 後段の cmp-long が v8/v9 = 0 に依存するため、全出口でゼロペアを復元する。
# =====================================================================

    :goto_4
    move-wide v10, v8

    if-eqz v0, :jp23_qdone

    iget-object v3, v0, Lcom/x/urt/items/post/a6;->m:Lcom/x/inlineactionbar/s1;

    if-eqz v3, :jp23_qdone

    iget-object v3, v3, Lcom/x/inlineactionbar/s1;->a:Lkotlinx/collections/immutable/b;

    if-eqz v3, :jp23_qdone

    invoke-interface {v3}, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;

    move-result-object v3

    :jp23_qloop
    invoke-interface {v3}, Ljava/util/Iterator;->hasNext()Z

    move-result v9

    if-eqz v9, :jp23_qfixup

    invoke-interface {v3}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    move-result-object v10

    check-cast v10, Lcom/x/models/n4;

    iget-object v11, v10, Lcom/x/models/n4;->a:Lcom/x/models/y5;

    sget-object v9, Lcom/x/models/y5;->Quote:Lcom/x/models/y5;

    if-eq v11, v9, :jp23_qloop

    iget-object v10, v10, Lcom/x/models/n4;->b:Ljava/lang/Long;

    if-eqz v10, :jp23_qfixup

    invoke-virtual {v10}, Ljava/lang/Long;->longValue()J

    move-result-wide v10

    const-wide/16 v8, 0x0

    goto :jp23_qdone

    :jp23_qfixup
    const-wide/16 v8, 0x0

    move-wide v10, v8

    :jp23_qdone


# =====================================================================
# 断片4 (B16-2): showPivot への引用数 OR
# 配置先: 同上 a0->d、showPivot (v6) の 0 側パス
# （isSelf=false かつ RT数=0 で v6=0 になる箇所）
# =====================================================================

    move v6, v4

    cmp-long v3, v10, v8

    if-gtz v3, :jp23_noshow

    move v6, v7

    :jp23_noshow
    goto :goto_6


# =====================================================================
# 断片5 (B16-2): ピボットラベルの差し替え（他者分岐）
# 配置先: 同上 a0->d、ラベル選択の他者分岐 (:cond_c)
# quoteCount > 0 のとき「{count}件の引用」+「を表示」。
# 0 件では従来の「引用を表示」(0x7f132e2d) のまま。
# =====================================================================

    :cond_c
    cmp-long v3, v10, v8

    if-gtz v3, :jp23_plainlabel

    const v0, 0x2a2e6d74

    const v3, 0x7f132dd3

    invoke-static {v0, v3, v1, v1, v4}, Lads_mobile_sdk/kn;->i(IILandroidx/compose/runtime/o0;Landroidx/compose/runtime/o0;Z)Ljava/lang/String;

    move-result-object v0

    invoke-static/range {v10 .. v11}, Ljava/lang/Long;->toString(J)Ljava/lang/String;

    move-result-object v3

    const-string v9, "{count}"

    invoke-virtual {v0, v9, v3}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;

    move-result-object v0

    const-string v3, "を表示"

    invoke-virtual {v0, v3}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    goto :goto_7

    :jp23_plainlabel
    const v0, 0x2a2e6d74

    const v3, 0x7f132e2d

    invoke-static {v0, v3, v1, v1, v4}, Lads_mobile_sdk/kn;->i(IILandroidx/compose/runtime/o0;Landroidx/compose/runtime/o0;Z)Ljava/lang/String;

    move-result-object v0

    goto :goto_7
