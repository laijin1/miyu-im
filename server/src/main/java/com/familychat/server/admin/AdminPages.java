package com.familychat.server.admin;

/**
 * 管理后台页面：纯 HTML/CSS/原生 JS，全部内嵌为 Java 字符串常量。
 *
 * <p><b>为什么内嵌而不是 staticFiles：</b>shadowJar 打包后资源目录解析容易随启动路径漂移；
 * 内嵌成常量则页面与代码同一次编译、同一个产物，不可能出现"jar 更新了但静态文件还是旧的"。
 * 也顺带满足"不引 npm、不用前端框架"的要求。
 *
 * <p>XSS 处理：所有来自数据库的文本都经 {@code esc()} 走 HTML 实体转义后再拼进 DOM，
 * 不依赖 innerHTML 的隐式转义。
 */
public final class AdminPages {

    private AdminPages() {
    }

    /** 全站共用样式。深色、紧凑、无外部依赖（不引 Google Fonts，离线局域网也能看）。 */
    private static final String CSS = """
            :root{
              --bg:#0f1115; --panel:#171a21; --panel2:#1e222b; --line:#2a2f3a;
              --fg:#e6e9ef; --muted:#8b94a7; --accent:#4f8cff; --ok:#2ecc71;
              --warn:#f5a623; --danger:#ff5c5c;
            }
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--fg);
              font:14px/1.55 "Segoe UI",system-ui,-apple-system,"Microsoft YaHei",sans-serif}
            a{color:var(--accent);text-decoration:none}
            a:hover{text-decoration:underline}
            header{display:flex;align-items:center;gap:18px;padding:12px 20px;
              background:var(--panel);border-bottom:1px solid var(--line);position:sticky;top:0;z-index:5}
            header .brand{font-weight:600;letter-spacing:.5px}
            header nav{display:flex;gap:14px;flex:1}
            header nav a{color:var(--muted);padding:4px 2px;border-bottom:2px solid transparent}
            header nav a.active{color:var(--fg);border-bottom-color:var(--accent)}
            header .who{color:var(--muted);font-size:13px}
            main{padding:20px;max-width:1240px;margin:0 auto}
            .card{background:var(--panel);border:1px solid var(--line);border-radius:10px;
              padding:16px;margin-bottom:16px}
            .card h2{margin:0 0 12px;font-size:15px;font-weight:600;color:var(--fg)}
            .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}
            .stat{background:var(--panel2);border:1px solid var(--line);border-radius:8px;padding:14px}
            .stat .k{color:var(--muted);font-size:12px;margin-bottom:6px}
            .stat .v{font-size:24px;font-weight:600}
            table{width:100%;border-collapse:collapse;font-size:13px}
            th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
            th{color:var(--muted);font-weight:500;white-space:nowrap}
            tr:hover td{background:#1b1f27}
            .muted{color:var(--muted)}
            .pill{display:inline-block;padding:1px 8px;border-radius:99px;font-size:12px;
              border:1px solid var(--line);background:var(--panel2)}
            .pill.ok{color:var(--ok);border-color:#245c3d}
            .pill.warn{color:var(--warn);border-color:#5c4724}
            .pill.danger{color:var(--danger);border-color:#5c2424}
            button{background:var(--panel2);color:var(--fg);border:1px solid var(--line);
              border-radius:6px;padding:5px 12px;cursor:pointer;font-size:13px}
            button:hover{border-color:var(--accent)}
            button.primary{background:var(--accent);border-color:var(--accent);color:#fff}
            button.danger{color:var(--danger);border-color:#5c2424}
            button:disabled{opacity:.5;cursor:not-allowed}
            input,select{background:#12151b;color:var(--fg);border:1px solid var(--line);
              border-radius:6px;padding:6px 10px;font-size:13px;font-family:inherit}
            .row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
            .pager{display:flex;gap:10px;align-items:center;margin-top:12px}
            .banner{padding:10px 14px;border-radius:8px;margin-bottom:16px;font-size:13px}
            .banner.warn{background:#2a2213;border:1px solid #5c4724;color:#ffd79a}
            .banner.err{background:#2a1414;border:1px solid #5c2424;color:#ffb3b3}
            .toast{position:fixed;right:20px;bottom:20px;background:var(--panel2);
              border:1px solid var(--line);border-left:3px solid var(--accent);
              border-radius:8px;padding:10px 16px;max-width:420px;z-index:50;display:none}
            .toast.err{border-left-color:var(--danger)}
            .modal-back{position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:60;
              display:none;align-items:center;justify-content:center}
            .modal{width:380px;background:var(--panel);border:1px solid var(--line);
              border-radius:10px;padding:18px}
            .modal h3{margin:0 0 6px;font-size:15px;font-weight:600}
            .modal label{display:block;color:var(--muted);font-size:12px;margin:12px 0 4px}
            .modal input{width:100%}
            .modal-actions{display:flex;gap:10px;justify-content:flex-end;margin-top:18px}
            .login-wrap{min-height:100vh;display:flex;align-items:center;justify-content:center}
            .login{width:340px}
            .login h1{font-size:18px;margin:0 0 4px}
            .login p{color:var(--muted);margin:0 0 18px;font-size:13px}
            .login label{display:block;color:var(--muted);font-size:12px;margin:12px 0 4px}
            .login input{width:100%}
            .login button{width:100%;margin-top:18px;padding:9px}
            pre{white-space:pre-wrap;word-break:break-all;margin:0;font-size:12px;color:var(--muted)}
            """;

    /** 登录页。不引任何外部资源，表单直接 POST 到 /admin/login。 */
    public static String loginPage(String error, boolean locked, long lockSeconds) {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>管理后台登录</title><style>").append(CSS).append("</style></head><body>")
                .append("<div class=\"login-wrap\"><div class=\"login card\">")
                .append("<h1>密语 管理后台</h1><p>独立管理员体系 · 与 App 账号不通用</p>");
        if (locked) {
            b.append("<div class=\"banner err\">登录已锁定，请 ").append(lockSeconds)
                    .append(" 秒后重试</div>");
        } else if (error != null && !error.isEmpty()) {
            b.append("<div class=\"banner err\">").append(esc(error)).append("</div>");
        }
        b.append("<form method=\"post\" action=\"/admin/login\">")
                .append("<label>用户名</label><input name=\"username\" autocomplete=\"username\" autofocus required>")
                .append("<label>密码</label><input name=\"password\" type=\"password\" ")
                .append("autocomplete=\"current-password\" required>")
                .append("<button class=\"primary\" type=\"submit\">登录</button>")
                .append("</form>")
                .append("<p style=\"margin-top:14px\">初始密码见服务端控制台或 ")
                .append("<code>data/admin_initial_password.txt</code></p>")
                .append("</div></div></body></html>");
        return b.toString();
    }

    /** 管理端主框架。视图切换全部在前端完成，服务端只负责鉴权与返回外壳。 */
    public static String shell(String username, boolean mustChangePw) {
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>密语 管理后台</title><style>__CSS__</style></head><body>
                <header>
                  <span class="brand">密语 管理后台</span>
                  <nav>
                    <a href="#/" data-v="dash">概览</a>
                    <a href="#/users" data-v="users">用户</a>
                    <a href="#/refunds" data-v="refunds">退款审批</a>
                    <a href="#/recharges" data-v="recharges">充值审批</a>
                    <a href="#/charges" data-v="charges">收款请求</a>
                    <a href="#/audit" data-v="audit">审计日志</a>
                    <a href="#/maintenance" data-v="maintenance">数据卫生</a>
                  </nav>
                  <span class="who"><span id="who">__NAME__</span><span id="who-hint">__HINT__</span></span>
                  <button id="btn-name">改账号名</button>
                  <button id="btn-pw">改密</button>
                  <button id="btn-logout" class="danger">退出</button>
                </header>
                <main>
                  <div id="banner"></div>
                  <div id="view"><p class="muted">加载中…</p></div>
                </main>
                <div class="toast" id="toast"></div>
                <div class="modal-back" id="modal-back"><div class="modal" id="modal"></div></div>
                <script>__JS__</script>
                </body></html>
                """
                .replace("__CSS__", CSS)
                .replace("__NAME__", esc(username))
                .replace("__HINT__", mustChangePw ? " · 初始密码未改" : "")
                .replace("__JS__", JS);
    }

    private static final String JS = """
            // ── 工具 ──
            const $ = (id) => document.getElementById(id);
            function esc(s){
              if (s === null || s === undefined) return '';
              return String(s).replace(/[&<>"']/g, c => (
                {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
            }
            function toast(msg, isErr){
              const t = $('toast');
              t.textContent = msg;
              t.className = 'toast' + (isErr ? ' err' : '');
              t.style.display = 'block';
              clearTimeout(t._h);
              t._h = setTimeout(() => { t.style.display = 'none'; }, 4000);
            }
            function fmtTime(ms){
              if (!ms) return '-';
              const d = new Date(Number(ms));
              const p = (n) => String(n).padStart(2,'0');
              return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate())+' '
                   + p(d.getHours())+':'+p(d.getMinutes())+':'+p(d.getSeconds());
            }
            function yuan(fen){
              if (fen === null || fen === undefined) return '-';
              return (Number(fen)/100).toFixed(2);
            }
            // 401 一律回登录页：会话过期后停在旧页面会让人误以为按钮坏了
            async function api(path, opts){
              const r = await fetch(path, Object.assign({credentials:'same-origin'}, opts||{}));
              if (r.status === 401){ location.href = '/admin/login'; throw new Error('未登录'); }
              const txt = await r.text();
              let data;
              try { data = txt ? JSON.parse(txt) : {}; } catch(e){ data = {raw: txt}; }
              if (!r.ok) throw new Error(data.error || ('HTTP ' + r.status));
              return data;
            }
            function post(path, body){
              return api(path, {method:'POST', headers:{'Content-Type':'application/json'},
                body: JSON.stringify(body || {})});
            }

            // ── 各视图 ──
            async function viewDash(){
              const d = await api('/admin/api/stats');
              const s = d.stats;
              let html = '<div class="card"><h2>概览</h2><div class="grid">';
              const cells = [
                ['注册用户', s.users], ['今日新增', s.users_today],
                ['单聊消息', s.messages], ['群聊消息', s.group_messages],
                ['群组数', s.groups], ['禁用账号', s.disabled],
                ['钱包余额合计(元)', yuan(s.balance_sum)],
                ['待处理退款', s.refunds_pending], ['待处理充值', s.recharges_pending],
                ['待处理收款请求', s.charges_pending],
                ['待付款订单', s.orders_pending], ['审计条数', s.audit_rows],
                ['托管池余额(元)', yuan(s.escrow_pool)],
                ['托管中订单', s.escrow_orders]
              ];
              for (const [k,v] of cells){
                html += '<div class="stat"><div class="k">'+esc(k)+'</div><div class="v">'+esc(v)+'</div></div>';
              }
              html += '</div>';
              // 托管账目自检：池余额与明细合计必须相等，否则说明有资金操作绕过了托管流程
              if (Number(s.escrow_pool) !== Number(s.escrow_detail_sum)) {
                html += '<div class="banner err" style="margin:12px 0 0">⚠ 托管账目不一致：'
                     +  '池余额 '+yuan(s.escrow_pool)+' 元，明细合计 '+yuan(s.escrow_detail_sum)
                     +  ' 元。请立即用 DbAdminTool 的 escrow-audit 排查。</div>';
              } else {
                html += '<p class="muted" style="margin:12px 0 0">托管账目自检通过：池余额与明细合计一致（'
                     +  yuan(s.escrow_detail_sum) + ' 元）。</p>';
              }
              html += '</div>';
              html += '<div class="card"><h2>服务信息</h2><table>'
                   +  '<tr><th>数据目录</th><td>'+esc(d.data_dir)+'</td></tr>'
                   +  '<tr><th>管理员</th><td>'+esc(d.admin.username)
                   +  '（上次登录 '+fmtTime(d.admin.last_login_at)+'）</td></tr>'
                   +  '<tr><th>初始密码</th><td>'
                   +  (d.admin.must_change_pw
                        ? '<span class="pill warn">未修改，请立即改密</span>'
                        : '<span class="pill ok">已修改</span>')
                   +  '</td></tr></table></div>';
              $('view').innerHTML = html;
            }

            let usersPage = 1;
            async function viewUsers(page){
              usersPage = page || usersPage;
              const q = $('u-q') ? $('u-q').value.trim() : '';
              const d = await api('/admin/api/users?page='+usersPage+'&size=20&q='+encodeURIComponent(q));
              let html = '<div class="card"><h2>用户列表（共 '+d.total+'）</h2>'
                + '<div class="row" style="margin-bottom:12px">'
                + '<input id="u-q" placeholder="搜索用户名/昵称/ID" value="'+esc(q)+'">'
                + '<button onclick="viewUsers(1)">搜索</button></div>'
                + '<table><tr><th>ID</th><th>用户名</th><th>昵称</th><th>余额(元)</th>'
                + '<th>状态</th><th>注册时间</th><th>最后在线</th><th>操作</th></tr>';
              for (const u of d.users){
                html += '<tr><td>'+u.id+'</td><td>'+esc(u.username)+'</td><td>'+esc(u.nickname)+'</td>'
                     +  '<td>'+(u.balance===null?'<span class="muted">无钱包</span>':yuan(u.balance))+'</td>'
                     +  '<td>'+(u.is_disabled
                          ? '<span class="pill danger">已禁用</span>'
                          : (u.is_online ? '<span class="pill ok">在线</span>' : '<span class="pill">离线</span>'))
                     +  '</td>'
                     +  '<td class="muted">'+fmtTime(u.created_at_ms)+'</td>'
                     +  '<td class="muted">'+esc(u.last_seen)+'</td>'
                     +  '<td class="row">'
                     +  '<button data-act="rename" data-id="'+u.id+'" data-n="'+esc(u.username)+'">改名</button>'
                     +  (u.is_disabled
                          ? '<button onclick="setDisabled('+u.id+',false)">启用</button>'
                          : '<button class="danger" onclick="setDisabled('+u.id+',true)">禁用</button>')
                     +  '<button class="danger" onclick="delUser('+u.id+')">删除</button>'
                     +  '</td></tr>';
              }
              html += '</table><div class="pager">'
                   +  '<button '+(usersPage<=1?'disabled':'')+' onclick="viewUsers('+(usersPage-1)+')">上一页</button>'
                   +  '<span class="muted">第 '+usersPage+' / '+d.pages+' 页</span>'
                   +  '<button '+(usersPage>=d.pages?'disabled':'')+' onclick="viewUsers('+(usersPage+1)+')">下一页</button>'
                   +  '</div></div>';
              $('view').innerHTML = html;
            }
            async function renameUser(id, oldName){
              const nickname = prompt('请输入「'+oldName+'」的新昵称（用户名不可改，昵称即对外显示名）');
              if (nickname === null) return;
              if (!nickname.trim()) { toast('昵称不能为空', true); return; }
              try { await post('/admin/api/users/'+id+'/rename', {nickname: nickname.trim()});
                toast('已改名'); viewUsers(); } catch(e){ toast(e.message, true); }
            }
            async function setDisabled(id, disabled){
              const why = prompt(disabled ? '禁用原因（写进审计）' : '启用原因（写进审计）');
              if (why === null) return;
              try { await post('/admin/api/users/'+id+'/'+(disabled?'disable':'enable'), {reason: why});
                toast(disabled?'已禁用':'已启用'); viewUsers(); } catch(e){ toast(e.message, true); }
            }
            async function delUser(id){
              const name = prompt('删除不可恢复！请输入该用户的用户名以确认删除：');
              if (name === null) return;
              const keep = confirm('确认删除该用户？其单聊消息、好友关系、群成员身份、钱包将一并清理。');
              if (!keep) return;
              try { await post('/admin/api/users/'+id+'/delete', {confirm_username: name});
                toast('已删除'); viewUsers(1); } catch(e){ toast(e.message, true); }
            }

            async function viewRefunds(page){
              const d = await api('/admin/api/refunds?page='+(page||1));
              let html = '<div class="card"><h2>退款审批（待卖家/平台处理 '+d.total+' 单）</h2>'
                + '<p class="muted">这里处理的是买家已申请、卖家尚未处理的退款（refund_status=pending）。'
                + '平台代批会直接从卖家余额扣回退给买家，并写审计。</p>'
                + '<table><tr><th>订单</th><th>买家</th><th>卖家</th><th>金额(元)</th>'
                + '<th>状态</th><th>理由</th><th>申请时间</th><th>操作</th></tr>';
              for (const o of d.orders){
                html += '<tr><td>'+esc(o.order_no)+'</td>'
                     +  '<td>'+esc(o.buyer_name)+' #'+o.buyer_id+'</td>'
                     +  '<td>'+esc(o.seller_name)+' #'+o.seller_id+'</td>'
                     +  '<td>'+yuan(o.amount)+'</td>'
                     +  '<td><span class="pill warn">'+esc(o.status)+'</span></td>'
                     +  '<td>'+esc(o.refund_reason)+'</td>'
                     +  '<td class="muted">'+fmtTime(o.created_at)+'</td>'
                     +  '<td class="row">'
                     +  '<button class="primary" onclick="approveRefund('+o.id+')">同意退款</button>'
                     +  '<button class="danger" onclick="rejectRefund('+o.id+')">驳回</button>'
                     +  '</td></tr>';
              }
              html += '</table></div>';
              $('view').innerHTML = html;
            }
            async function approveRefund(id){
              const why = prompt('同意退款的备注（写进审计，可留空）');
              if (why === null) return;
              try { await post('/admin/api/refunds/'+id+'/approve', {reason: why});
                toast('已退款'); viewRefunds(); } catch(e){ toast(e.message, true); }
            }
            async function rejectRefund(id){
              const why = prompt('驳回理由（写进审计）');
              if (why === null) return;
              try { await post('/admin/api/refunds/'+id+'/reject', {reason: why});
                toast('已驳回'); viewRefunds(); } catch(e){ toast(e.message, true); }
            }

            async function viewRecharges(page){
              const d = await api('/admin/api/recharges?page='+(page||1));
              let html = '<div class="card"><h2>充值审批</h2>'
                + '<p class="muted">App 侧充值目前只对白名单账号自助开放（单次上限 '
                + '100 元），平台在此把人工收款转成余额，不需要用户操作。</p>'
                + '<h2 style="margin-top:18px">手动入账</h2>'
                + '<div class="row"><input id="rc-user" placeholder="用户 ID" style="width:120px">'
                + '<input id="rc-amount" placeholder="金额(元)" style="width:120px">'
                + '<input id="rc-memo" placeholder="备注" style="flex:1;min-width:180px">'
                + '<button class="primary" onclick="doRecharge()">入账</button></div>'
                + '<p class="muted" style="margin-top:8px">入账会写 wallet 流水 + 审计日志，'
                + '并推送 App 刷新余额；单次上限 10000 元。</p>';
              if (d.recent && d.recent.length){
                html += '<h2 style="margin-top:20px">最近入账</h2>'
                     +  '<table><tr><th>时间</th><th>用户</th><th>金额(元)</th><th>备注</th></tr>';
                for (const r of d.recent){
                  html += '<tr><td class="muted">'+fmtTime(r.created_at)+'</td>'
                       +  '<td>'+esc(r.to_user)+'</td><td>'+yuan(r.amount)+'</td>'
                       +  '<td>'+esc(r.memo)+'</td></tr>';
                }
                html += '</table>';
              }
              html += '</div>';
              $('view').innerHTML = html;
            }
            async function doRecharge(){
              const userId = $('rc-user').value.trim();
              const amount = $('rc-amount').value.trim();
              const memo = $('rc-memo').value.trim();
              if (!userId || !amount) { toast('请填用户 ID 与金额', true); return; }
              if (!confirm('确认给用户 '+userId+' 入账 '+amount+' 元？')) return;
              try { await post('/admin/api/recharges', {user_id: Number(userId),
                amount: Number(amount), memo: memo});
                toast('已入账'); viewRecharges(); } catch(e){ toast(e.message, true); }
            }

            async function viewCharges(page){
              const d = await api('/admin/api/charges?page='+(page||1));
              let html = '<div class="card"><h2>收款请求（待处理 '+d.total+'）</h2>'
                + '<p class="muted">商家发起、等待付款码主人确认。平台代确认会从主人余额扣款转给商家。</p>'
                + '<table><tr><th>ID</th><th>商家</th><th>付款人</th><th>金额(元)</th>'
                + '<th>备注</th><th>创建时间</th><th>状态</th><th>操作</th></tr>';
              for (const r of d.requests){
                html += '<tr><td>'+r.id+'</td>'
                     +  '<td>'+esc(r.from_name)+' #'+r.from_user+'</td>'
                     +  '<td>'+esc(r.to_name)+' #'+r.to_user+'</td>'
                     +  '<td>'+yuan(r.amount)+'</td><td>'+esc(r.memo)+'</td>'
                     +  '<td class="muted">'+fmtTime(r.created_at)+'</td>'
                     +  '<td><span class="pill '+(r.status==='pending'?'warn':'')+'">'+esc(r.status)+'</span></td>'
                     +  '<td class="row">'
                     +  (r.status==='pending'
                          ? '<button class="primary" onclick="approveCharge('+r.id+')">代确认</button>'
                          + '<button class="danger" onclick="declineCharge('+r.id+')">拒绝</button>'
                          : '<span class="muted">-</span>')
                     +  '</td></tr>';
              }
              html += '</table></div>';
              $('view').innerHTML = html;
            }
            async function approveCharge(id){
              if (!confirm('确认代该用户付款（扣其余额）？')) return;
              try { await post('/admin/api/charges/'+id+'/approve', {}); toast('已确认');
                viewCharges(); } catch(e){ toast(e.message, true); }
            }
            async function declineCharge(id){
              const why = prompt('拒绝理由（写进审计）');
              if (why === null) return;
              try { await post('/admin/api/charges/'+id+'/decline', {reason: why});
                toast('已拒绝'); viewCharges(); } catch(e){ toast(e.message, true); }
            }

            let auditPage = 1;
            async function viewAudit(page){
              auditPage = page || auditPage;
              const action = $('a-action') ? $('a-action').value : '';
              const kw = $('a-kw') ? $('a-kw').value.trim() : '';
              const d = await api('/admin/api/audit?page='+auditPage+'&size=50&action='
                + encodeURIComponent(action)+'&q='+encodeURIComponent(kw));
              let opts = '<option value="">全部动作</option>';
              for (const a of d.actions){
                opts += '<option value="'+esc(a)+'"'+(a===action?' selected':'')+'>'+esc(a)+'</option>';
              }
              let html = '<div class="card"><h2>审计日志（共 '+d.total+' 条）</h2>'
                + '<div class="row" style="margin-bottom:12px">'
                + '<select id="a-action">'+opts+'</select>'
                + '<input id="a-kw" placeholder="搜索目标/详情/操作人" value="'+esc(kw)+'">'
                + '<button onclick="viewAudit(1)">筛选</button></div>'
                + '<table><tr><th>ID</th><th>时间</th><th>操作人</th><th>动作</th>'
                + '<th>目标</th><th>详情</th><th>来源 IP</th></tr>';
              for (const r of d.logs){
                html += '<tr><td>'+r.id+'</td><td class="muted">'+fmtTime(r.created_at)+'</td>'
                     +  '<td>'+esc(r.admin_name||('#'+r.admin_id))+'</td>'
                     +  '<td><span class="pill">'+esc(r.action)+'</span></td>'
                     +  '<td>'+esc(r.target)+'</td>'
                     +  '<td><pre>'+esc(r.detail)+'</pre></td>'
                     +  '<td class="muted">'+esc(r.ip)+'</td></tr>';
              }
              html += '</table><div class="pager">'
                   +  '<button '+(auditPage<=1?'disabled':'')+' onclick="viewAudit('+(auditPage-1)+')">上一页</button>'
                   +  '<span class="muted">第 '+auditPage+' / '+d.pages+' 页</span>'
                   +  '<button '+(auditPage>=d.pages?'disabled':'')+' onclick="viewAudit('+(auditPage+1)+')">下一页</button>'
                   +  '</div></div>';
              $('view').innerHTML = html;
            }

            // ── 路由 ──
            async function viewMaintenance(){
              $('view').innerHTML = '<div class="card"><h2>数据卫生（审计第 5 条）</h2>'
                + '<p class="muted">扫描两类孤儿：① 数据库里指向已不存在的群/消息/用户的回执行；'
                + '② chat_files 下已无任何数据库行引用的磁盘文件。'
                + '扫描<b>不会</b>修改任何数据；执行清理前会自动做一次数据库备份。</p>'
                + '<p class="muted"><b>注意</b>：App 上传附件的真实 URL 从未写入数据库'
                + '（只存了原始文件名 + E2EE 密文），所以"库里查不到引用"<b>不能证明</b>文件没在用。'
                + '因此默认清理<b>只删数据库孤儿行，不动磁盘文件</b>。</p>'
                + '<div class="row"><button class="primary" onclick="runScan()">扫描（只读）</button>'
                + '<button class="danger" onclick="runCleanup(false)">清理孤儿行（安全）</button>'
                + '<button class="danger" onclick="runCleanup(true)">清理孤儿行 + 删磁盘文件</button></div>'
                + '<div id="maint-out" style="margin-top:14px"></div></div>';
            }
            async function runScan(){
              try {
                const d = await post('/admin/api/maintenance/scan', {});
                $('maint-out').innerHTML = renderReport(d.report, true);
              } catch(e){ toast(e.message, true); }
            }
            async function runCleanup(withFiles){
              const warn = withFiles
                ? '警告：将删除磁盘上"数据库中查不到引用"的附件，而这些可能正是用户还在用的文件（无法恢复）。继续？'
                : '将删除数据库中的孤儿行（先自动备份数据库）。继续？';
              if (!confirm(warn)) return;
              const c = prompt('请输入 CONFIRM 以确认执行清理');
              if (c === null) return;
              try {
                const d = await post('/admin/api/maintenance/cleanup',
                  {confirm: c, delete_files: !!withFiles});
                toast('清理完成，备份：' + d.backup);
                $('maint-out').innerHTML = renderReport(d.report, false)
                  + '<p class="muted">备份文件：' + esc(d.backup) + '</p>';
              } catch(e){ toast(e.message, true); }
            }
            function renderReport(r, isDry){
              if (!r) return '<p class="muted">无数据</p>';
              let h = '<table><tr><th>项目</th><th>数量</th></tr>';
              const row = (k,v) => '<tr><td>'+esc(k)+'</td><td>'+esc(v)+'</td></tr>';
              h += row('库内孤儿总数' + (isDry ? '（未清理）' : '（清理前统计）'), r.dbOrphanTotal);
              for (const [k,v] of Object.entries(r.dbOrphanCounts || {})) h += row('  ' + k, v);
              if (r.dbOrphanCleaned){
                for (const [k,v] of Object.entries(r.dbOrphanCleaned)) h += row(' 已清理 ' + k, v);
              }
              h += row('磁盘未引用文件数', r.unreferencedFileCount);
              h += row('磁盘未引用体积(字节)', r.unreferencedBytes);
              if (r.diskReclaim){
                h += row('实际删除文件', r.diskReclaim.deleted);
                h += row('跳过(仍被引用)', r.diskReclaim.skippedReferenced);
                h += row('释放字节', r.diskReclaim.bytesFreed);
              }
              h += '</table>';
              if (r.diskReclaimSkipped) h += '<p class="banner warn">' + esc(r.diskReclaimSkipped) + '</p>';
              if (r.unreferencedFilesListed && r.unreferencedFilesListed.length){
                h += '<p class="muted" style="margin-top:10px">未引用文件（最多列 100 个）：</p><pre>'
                  +  esc(r.unreferencedFilesListed.join('\\n')) + '</pre>';
              }
              h += '<p class="muted" style="margin-top:8px">chat_files: ' + esc(r.chatDir || '') + '</p>';
              return h;
            }

            const ROUTES = {dash:viewDash, users:viewUsers, refunds:viewRefunds,
                            recharges:viewRecharges, charges:viewCharges, audit:viewAudit,
                            maintenance:viewMaintenance};
            function route(){
              const name = (location.hash.replace('#/','') || 'dash').split('?')[0];
              const fn = ROUTES[name] || viewDash;
              for (const a of document.querySelectorAll('header nav a')){
                a.className = (a.getAttribute('data-v') === name) ? 'active' : '';
              }
              Promise.resolve(fn(1)).catch(e => { $('view').innerHTML =
                '<div class="banner err">加载失败：'+esc(e.message)+'</div>'; });
            }
            window.addEventListener('hashchange', route);

            // 表格内的按钮统一走事件委托：用户数据里可能带引号/尖括号，
            // 内联 onclick 拼字符串会把 HTML 转义和 JS 字符串转义搅在一起（XSS 温床）。
            document.addEventListener('click', (ev) => {
              const btn = ev.target.closest('button[data-act]');
              if (!btn) return;
              const id = Number(btn.dataset.id);
              const act = btn.dataset.act;
              if (act === 'rename') renameUser(id, btn.dataset.n || '');
            });

            // ── 模态表单 ──
            // 改密/改名各自都有 3 种以上失败原因（当前密码错 / 格式不合法 / 用户名已存在），
            // 裸 prompt() 一次只能收一个值、也无法把"到底哪一项错了"回显在同一处，故改用
            // 页面内表单。返回 Promise：点确定给 {字段名:值}，取消/点遮罩/按 Esc 给 null。
            function openModal(title, fields){
              return new Promise((resolve) => {
                const back = $('modal-back'), box = $('modal');
                box.innerHTML = '<h3>' + esc(title) + '</h3>' + fields.map(f =>
                    '<label>' + esc(f.label) + '</label><input type="' + (f.type || 'text')
                    + '" data-n="' + esc(f.name) + '" autocomplete="off">').join('')
                  + '<div class="modal-actions"><button id="m-cancel">取消</button>'
                  + '<button class="primary" id="m-ok">确定</button></div>';
                const inputs = box.querySelectorAll('input');
                back.style.display = 'flex';
                if (inputs[0]) inputs[0].focus();
                const close = (v) => { back.style.display = 'none'; box.innerHTML = ''; resolve(v); };
                $('m-cancel').onclick = () => close(null);
                back.onclick = (ev) => { if (ev.target === back) close(null); };
                box.onkeydown = (ev) => {
                  if (ev.key === 'Enter'){ ev.preventDefault(); $('m-ok').click(); }
                  if (ev.key === 'Escape') close(null);
                };
                $('m-ok').onclick = () => {
                  const out = {};
                  inputs.forEach(i => { out[i.dataset.n] = i.value; });
                  close(out);
                };
              });
            }

            $('btn-logout').onclick = async () => {
              try { await post('/admin/logout', {}); } catch(e){ /* 忽略 */ }
              location.href = '/admin/login';
            };
            $('btn-pw').onclick = async () => {
              const v = await openModal('修改密码', [
                {name:'old_password', label:'当前密码', type:'password'},
                {name:'new_password', label:'新密码（至少 8 位，不能是纯数字）', type:'password'}]);
              if (!v) return;
              if ((v.new_password || '').length < 8) { toast('新密码至少 8 位', true); return; }
              try { await post('/admin/api/password', v); toast('密码已修改'); }
              catch(e){ toast(e.message, true); }
            };
            $('btn-name').onclick = async () => {
              const v = await openModal('修改账号名', [
                {name:'new_username', label:'新账号名（3-32 位字母、数字、. _ -）'},
                {name:'password', label:'当前密码', type:'password'}]);
              if (!v) return;
              try {
                const r = await post('/admin/api/username', v);
                // 头部名字是服务端渲染进 __NAME__ 的静态文本，改名后就地更新，
                // 免得页面上还挂着旧名（会话在服务端已刷新，刷新页面同样一致）
                $('who').textContent = r.username;
                toast('账号名已修改');
              } catch(e){ toast(e.message, true); }
            };

            route();
            """;

    /** HTML 实体转义（服务端侧）。 */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
