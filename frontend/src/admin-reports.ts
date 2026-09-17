import { computed, onMounted, ref } from 'vue';
import { formatDateTime } from './time';
import './management.css';

/** 必须与 AdminReportController 的 LIMIT 12 保持一致。 */
const PAGE_SIZE = 12;

export default {
  props: ['request', 'reviewId'],
  setup(props:any) {
    const rows=ref<any[]>([]), total=ref(0), page=ref(1), busy=ref(false), error=ref(''), selected=ref<any>(null);
    const filters=ref({q:'',status:'',from:'',to:'',reviewId:props.reviewId||0});
    const labels:Record<string,string>={OPEN:'待填写',DRAFT:'草稿',SUBMITTED:'已提交',LATE_SUBMITTED:'迟交',MISSED:'未按期提交',CANCELLED:'已取消',PLANNED:'未开始',ACTIVE:'进行中',AWAITING_DECISION:'待决策',COMPLETED:'已结束'};
    const titles:Record<string,string>={competition:'参加竞赛',horizontalProject:'横向课题',verticalProject:'纵向课题',learning:'学习内容',reading:'论文与资料',outcome:'成果报告',nextWeek:'下周计划'};
    const sections=computed(()=>Object.entries(titles).map(([key,title])=>({key,title,content:selected.value?.sections?.[key]})));
    const run=async(fn:()=>Promise<void>)=>{if(busy.value)return;busy.value=true;error.value='';try{await fn();}catch(e:any){error.value=e.message;}finally{busy.value=false;}};
    const load=async(next:number)=>{const query=new URLSearchParams({...filters.value,reviewId:String(filters.value.reviewId),page:String(next)});const result=await props.request('/admin/reports?'+query);rows.value=result.items;total.value=result.total;page.value=result.page;};
    const search=(next=1)=>run(()=>load(next));
    const reset=()=>{filters.value={q:'',status:'',from:'',to:'',reviewId:0};search();};
    const open=(id:number)=>run(async()=>{selected.value=await props.request('/admin/reports/'+id);});
    const safeUrl=(value:any)=>{try{const url=new URL(String(value));return ['https:','http:'].includes(url.protocol)?url.href:null;}catch{return null;}};
    const totalPages=computed(()=>Math.max(1,Math.ceil(total.value/PAGE_SIZE)));
    // 时间展示统一走 time.ts。修复前这里是原样截断字符串，而 projects 页补了 'Z'
    // 再按时区格式化，同一个后端时间在两处相差 8 小时。
    const time=(value:any)=>formatDateTime(value);
    onMounted(()=>search());
    return{rows,total,page,busy,error,selected,filters,labels,sections,search,reset,open,safeUrl,time,totalPages};
  },
  template:`<section class="management report-archive" :aria-busy="busy">
    <header class="mg-heading"><div><p class="mg-kicker">材料档案 / 管理员</p><h1>报告查询</h1><p>按成员查阅周报，保留已结束考察的历史材料。</p></div><span class="mg-access">仅管理员可见</span></header>
    <p v-if="error" class="mg-alert" role="alert">{{error}}</p>
    <template v-if="!selected">
      <form class="mg-filters" @submit.prevent="search()"><label class="mg-search">成员<input v-model="filters.q" placeholder="搜索姓名或学号" maxlength="100" :disabled="busy"></label><label>提交状态<select v-model="filters.status" :disabled="busy"><option value="">全部状态</option><option v-for="s in ['OPEN','DRAFT','SUBMITTED','LATE_SUBMITTED','MISSED','CANCELLED']" :value="s">{{labels[s]}}</option></select></label><label>截止日期起<input type="date" v-model="filters.from" :disabled="busy"></label><label>截止日期止<input type="date" v-model="filters.to" :disabled="busy"></label><button class="mg-button primary" :disabled="busy">查询</button><button type="button" class="mg-button" :disabled="busy" @click="reset">重置</button></form>
      <div class="mg-results-bar"><span>{{busy?'正在查询…':'共 '+total+' 份周报任务'}}</span><span v-if="filters.reviewId">当前仅查看考察 #{{filters.reviewId}}</span><span v-else>包含未提交任务和历史考察</span></div>
      <div class="mg-table-wrap"><table class="mg-table"><thead><tr><th>成员</th><th>周次 / 截止日期</th><th>提交状态</th><th>考察状态</th><th>材料</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td><b>{{row.name}}</b><small>{{row.student_no}}</small></td><td>第 {{row.period_index}} 周<small>{{time(row.due_at)}}</small></td><td><span class="mg-status" :data-status="row.status">{{labels[row.status]||row.status}}</span></td><td>{{labels[row.review_status]||row.review_status}}</td><td><button class="mg-link" :disabled="busy" @click="open(row.id)">{{row.has_report?'查看材料':'查看任务'}} →</button></td></tr></tbody></table><div v-if="!rows.length&&!busy" class="mg-empty"><h2>没有符合条件的报告</h2><p>试试调整成员、日期或状态；发起考察后会自动生成周报任务。</p></div></div>
      <nav class="mg-pagination" aria-label="报告分页"><span>第 {{page}} / {{totalPages}} 页</span><button class="mg-button" :disabled="busy||page<=1" @click="search(page-1)">上一页</button><button class="mg-button" :disabled="busy||page>=totalPages" @click="search(page+1)">下一页</button></nav>
    </template>
    <template v-else><button class="mg-link mg-back" @click="selected=null">← 返回查询结果</button><article class="report-document"><header class="report-document-head"><div><p class="mg-kicker">{{selected.student_no}} · 第 {{selected.period_index}} 周</p><h2>{{selected.name}}的考察周报</h2></div><span class="mg-status" :data-status="selected.status">{{labels[selected.status]||selected.status}}</span></header><dl class="mg-facts"><div><dt>截止时间</dt><dd>{{time(selected.due_at)}}</dd></div><div><dt>提交时间</dt><dd>{{time(selected.submitted_at)}}</dd></div><div><dt>考察状态</dt><dd>{{labels[selected.review_status]||selected.review_status}}</dd></div></dl><div class="report-context"><template v-if="selected.decision_note"><b>考察处理说明</b><p>{{selected.decision_note}}</p></template><b>考察原因</b><p>{{selected.reason}}</p><template v-if="selected.requirements"><b>补充要求</b><p>{{selected.requirements}}</p></template></div>
    <p v-if="selected.content_error" class="mg-alert" role="alert">{{selected.content_error}}</p><p v-if="selected.saved_status==='DRAFT'" class="mg-notice">当前内容为已保存草稿，不代表成员已正式提交。</p><div v-if="!selected.has_report" class="mg-empty"><h3>尚无已保存材料</h3><p>此处仅有周报任务，成员还没有保存或提交内容。</p></div>
    <div v-else class="report-reading"><section v-for="s in sections" :key="s.key" class="report-chapter"><h3>{{s.title}}</h3><div><p v-if="!s.content" class="mg-muted">此项未填写</p><p v-else-if="s.content.omitted" class="mg-muted">本周无此项内容</p><template v-else><h4>{{s.content.title||'未填写标题'}}</h4><p>{{s.content.detail||'未填写详细说明'}}</p><p v-if="s.content.collaborators" class="report-collaborators">协作同学：{{s.content.collaborators}}</p><a v-if="safeUrl(s.content.url)" :href="safeUrl(s.content.url)" target="_blank" rel="noopener noreferrer" class="mg-link">打开成果链接 ↗</a><p v-else-if="s.content.url" class="mg-muted">成果链接：{{s.content.url}}</p></template></div></section></div><footer class="report-document-footer">只读档案 · 管理员不能代替成员修改报告</footer></article></template>
  </section>`
};
