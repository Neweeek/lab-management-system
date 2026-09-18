import { computed, onMounted, ref } from 'vue';
import { localDateFromToday, toLocalDateInput } from './time';
import './management.css';
import './duty-week.css';

/**
 * 管理端值班排班。
 *
 * 只有管理员能看到全员课表与值班表；排班依据是"该讲课没课的成员"。
 */
export default {
  props: ['request'],
  setup(props: any) {
    const date = ref(toLocalDateInput());
    const week = ref<any>({days: [], periods: [], duty: [], unimported: []});
    const weekOffset = ref(0);
    const selected = ref<any>(null);
    const selectedMember = ref('');
    const loadWeek = async () => {
      week.value = term.value ? await props.request('/courses/admin/week?week=' + weekOffset.value)
          : {days: [], periods: [], duty: [], unimported: []};
      selectedMember.value = '';
    };
    const goWeek = (delta: number) => run(async () => {
      weekOffset.value = Math.max(-52, Math.min(52, weekOffset.value + delta));
      selected.value = null; await loadWeek();
    });
    const jumpWeek = (event: Event) => run(async () => {
      const number = Number((event.target as HTMLSelectElement).value);
      const today = new Date(toLocalDateInput() + 'T00:00');
      today.setDate(today.getDate() - (today.getDay() + 6) % 7);
      weekOffset.value = Math.round((new Date(term.value.start_date + 'T00:00').getTime() - today.getTime()) / 604800000) + number - 1;
      selected.value = null; await loadWeek();
    });
    const teachingWeeks = computed(() => term.value ? Math.ceil((Date.parse(term.value.end_date) - Date.parse(term.value.start_date) + 86400000) / 604800000) : 0);
    const assignedAt = (date: string, period: number) => (week.value.duty || []).filter((a: any) => a.date === date && a.period_no === period);
    const selectedCell = computed(() => {
      const d = week.value.days.find((d: any) => d.date === selected.value?.date);
      return d?.periods.find((p: any) => p.period_no === selected.value?.period);
    });
    const freeChoices = computed(() => (selectedCell.value?.free_members || []).filter((m: any) =>
      !(week.value.duty || []).some((a: any) => a.date === selected.value?.date && a.user_id === m.id)));
    const selectSlot = (d: any, p: any) => { selected.value = {date:d.date, period:p.period_no}; selectedMember.value = ''; };
    const assignSelected = () => run(async () => {
      if (!selected.value || !selectedMember.value) return;
      await props.request('/duty/admin/assign', {method:'POST', body:JSON.stringify({
        date:selected.value.date, periodNo:selected.value.period, userId:Number(selectedMember.value)})});
      message.value = '值班已安排'; await loadWeek(); await loadFairness();
    });
    const day = ref<any>({ periods: [] });
    const load = ref<any[]>([]);
    const members = ref<any[]>([]);
    const from = ref(localDateFromToday(0));
    const to = ref(localDateFromToday(13));
    const busy = ref(false);
    const error = ref('');
    const message = ref('');
    const warnings = ref<string[]>([]);
    /** 因未导入课表而未参与自动排班的成员姓名。 */
    const excluded = ref<string[]>([]);
    // 每个讲课的下拉选择：{ periodNo: userId }
    const picks = ref<Record<number, string>>({});

    // 学期配置：它是"第几周"的基准，成员端手动添加课程依赖它
    const term = ref<any>(null);
    /** 全部学期，用于列表管理（切换当前 / 删除） */
    const allTerms = ref<any[]>([]);
    const termForm = ref<any>({ name: '', startDate: '', endDate: '' });

    const run = async (fn: () => Promise<void>) => {
      if (busy.value) return;
      busy.value = true;
      error.value = '';
      try {
        await fn();
      } catch (e: any) {
        error.value = e.message;
      } finally {
        busy.value = false;
      }
    };

    const loadDay = async () => {
      day.value = await props.request('/duty/admin/day?date=' + encodeURIComponent(date.value));
      picks.value = {};
      await loadWeek();
    };

    const loadFairness = async () => {
      load.value = await props.request(
        '/duty/admin/load?from=' + encodeURIComponent(from.value) + '&to=' + encodeURIComponent(to.value));
    };

    const loadTerm = async () => {
      const terms = await props.request('/courses/terms');
      term.value = terms.current;
      allTerms.value = terms.terms || [];
    };

    /** 切换当前学期。 */
    const useTerm = (row: any) =>
      run(async () => {
        const result = await props.request('/courses/admin/terms/' + row.id + '/current', { method: 'POST' });
        message.value = result.message;
        await loadTerm();
        selected.value = null; await loadWeek();
      });

    /**
     * 删除学期。破坏性操作：会连带删掉该学期所有成员的课程与上课时间，
     * 因此必须二次确认并明确告知代价，而不是直接删。
     */
    const removeTerm = (row: any) => {
      const warning = '确认删除学期「' + row.name + '」（' + row.start_date + ' 至 ' + row.end_date + '）？\n\n'
          + '这会同时删除该学期内【所有成员】的课程与上课时间，且不可恢复。\n'
          + '如果只是想换一个当前学期，请用「设为当前」。';
      if (!window.confirm(warning)) return;
      run(async () => {
        const result = await props.request('/courses/admin/terms/' + row.id + '/delete', { method: 'POST' });
        message.value = result.message;
        await loadTerm();
        selected.value = null; await loadWeek();
      });
    };

    /**
     * 配置学期。开始日期必须是周一，否则"第几周"会整体错位，
     * 因此这里在前端也拦一道，给出即时提示而不是等后端 400。
     */
    const saveTerm = () =>
      run(async () => {
        const start = termForm.value.startDate;
        if (!start) throw new Error('请填写学期开始日期');
        const day = new Date(start + 'T00:00').getDay();   // 0=周日, 1=周一
        if (day !== 1) throw new Error('学期开始日期必须是周一（当前选的是' + ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][day] + '）');
        const result = await props.request('/courses/admin/terms', {
          method: 'POST',
          body: JSON.stringify({
            name: termForm.value.name,
            startDate: termForm.value.startDate,
            endDate: termForm.value.endDate,
            makeCurrent: true
          })
        });
        message.value = result.message + '（共 ' + result.weeks + ' 周）';
        termForm.value = { name: '', startDate: '', endDate: '' };
        await loadTerm();
        selected.value = null; await loadWeek();
      });

    const refresh = () =>
      run(async () => {
        const list = await props.request('/admin/members');
        members.value = (list || []).filter((m: any) => m.role === 'MEMBER' && m.approved === 1);
        await loadTerm();
        await loadDay();
        await loadFairness();
      });

    /** 自动生成：按课表把没课的人排到各讲课。 */
    const generate = () =>
      run(async () => {
        warnings.value = [];
        excluded.value = [];
        const result = await props.request('/duty/admin/generate', {
          method: 'POST',
          body: JSON.stringify({ from: from.value, to: to.value })
        });
        message.value = result.message;
        warnings.value = result.understaffed_slots || [];
        excluded.value = result.excluded_no_timetable || [];
        await loadDay();
        await loadFairness();
      });

    const assign = (periodNo: number) =>
      run(async () => {
        const userId = picks.value[periodNo];
        if (!userId) return;
        await props.request('/duty/admin/assign', {
          method: 'POST',
          body: JSON.stringify({ date: date.value, periodNo, userId: Number(userId) })
        });
        await loadDay();
        await loadFairness();
      });

    const remove = (assignmentId: number) =>
      run(async () => {
        await props.request('/duty/admin/assignments/' + assignmentId + '/remove', { method: 'POST' });
        await loadDay();
        await loadFairness();
      });

    /** 该讲课还没被安排的人，用于下拉候选。 */
    const candidatesFor = (period: any) => {
      const taken = new Set((period.assigned || []).map((a: any) => a.user_id));
      return members.value.filter((m: any) => !taken.has(m.id));
    };

    const onDateChange = () => run(loadDay);
    const understaffed = computed(() => (day.value.periods || []).filter((p: any) => p.understaffed).length);
    const totalAssigned = computed(() => day.value.total || 0);
    const maxLoad = computed(() => (load.value.length ? Math.max(...load.value.map((r: any) => r.duty_count)) : 0));
    const minLoad = computed(() => (load.value.length ? Math.min(...load.value.map((r: any) => r.duty_count)) : 0));

    onMounted(refresh);

    return {
      week, weekOffset, selected, selectedCell, selectedMember, freeChoices, assignedAt, goWeek, jumpWeek, teachingWeeks, selectSlot, assignSelected,
      date, day, load, members, from, to, busy, error, message, warnings, excluded, picks,
      term, termForm, saveTerm, allTerms, useTerm, removeTerm,
      refresh, generate, assign, remove, candidatesFor, onDateChange,
      understaffed, totalAssigned, maxLoad, minLoad, loadDay, loadFairness
    };
  },
  template: `<section class="management duty-admin" :aria-busy="busy">
    <header class="mg-heading"><div><p class="mg-kicker">实验室 / 值班排班</p><h1>值班排班</h1><p>按周查看谁没课，点选时段安排值班。每讲最多4人，每人每天最多一次。</p></div><button class="mg-button" :disabled="busy" @click="refresh">刷新</button></header>
    <p v-if="error" class="mg-alert" role="alert">{{error}}</p>
    <p v-if="message" class="mg-notice" role="status">{{message}}</p>
    <div v-if="excluded.length" class="mg-alert" role="alert"><b>以下 {{excluded.length}} 人未导入课表，未参与自动排班：</b>{{excluded.join('、')}}
      <p>系统无法判断他们什么时候有课，若把他们当作空闲可能把值班排到上课时间。请提醒他们在「我的课程表」中导入本学期的 .ics 文件或手动添加真实课程。</p></div>
    <div v-if="warnings.length" class="mg-alert" role="alert"><b>以下时段没人可排（候选人都有课或已排满），请手动处理：</b><ul><li v-for="w in warnings" :key="w">{{w}}</li></ul></div>

    <article class="mg-surface duty-week-panel">
      <div class="mg-section-title"><h2>每周空闲课表</h2><span>{{term?.name || '请先配置学期'}} · {{week.week_label}}</span></div>
      <div class="mg-filters">
        <button class="mg-button" :disabled="busy||!term||weekOffset<=-52" @click="goWeek(-1)">上一周</button>
        <button class="mg-button" :disabled="busy||!term" @click="goWeek(-weekOffset)">本周</button>
        <button class="mg-button" :disabled="busy||!term||weekOffset>=52" @click="goWeek(1)">下一周</button>
        <label>教学周<select :value="week.days[0]?.week_number || ''" :disabled="busy||!term" @change="jumpWeek"><option value="" disabled>选择教学周</option><option v-for="n in teachingWeeks" :key="n" :value="n">第 {{n}} 周</option></select></label>
      </div>
      <p class="mg-muted">格内列出本学期已导入课表且此时没课的学生。蓝色为已安排值班；点击格子即可选人。每人每天最多一次，周末仅供查看。</p>
      <p v-if="week.unimported.length" class="mg-alert"><b>本学期未导入（{{week.unimported.length}}人）</b>：{{week.unimported.map(m=>m.name).join('、')}}。不计入空闲人数，不参与排班。</p>
      <div v-if="selected && selectedCell" class="duty-slot-editor" role="region" aria-label="安排所选时段">
        <div><h3>{{selected.date}} · 第{{selected.period}}讲</h3><p>空闲 {{selectedCell.free_count}} 人 · 已安排 {{assignedAt(selected.date,selected.period).length}} / 4 人</p></div>
        <label>选择空闲学生<select v-model="selectedMember" :disabled="busy||!selectedCell.schedulable"><option value="">请选择</option><option v-for="m in freeChoices" :key="m.id" :value="m.id">{{m.name}} · {{m.student_no}}</option></select></label>
        <button class="mg-button primary" :disabled="busy||!selectedMember||!selectedCell.schedulable||assignedAt(selected.date,selected.period).length>=4" @click="assignSelected">安排值班</button>
        <p v-if="!selectedCell.schedulable">非当前有效学期的工作日，仅供查看。</p>
        <p v-else-if="!freeChoices.length">暂无可选学生：空闲学生可能当天已有值班。</p>
        <div class="duty-slot-assigned"><span v-for="a in assignedAt(selected.date,selected.period)" :key="a.assignment_id" class="duty-chip">{{a.name}}<button class="mg-link danger" :disabled="busy" @click="remove(a.assignment_id)" :aria-label="'取消'+a.name+'的值班'">取消</button></span></div>
      </div>
      <div class="duty-week-scroll" tabindex="0" aria-label="每周空闲课表，可横向滚动">
        <table class="duty-week-grid"><thead><tr><th scope="col">讲课 / 时间</th><th v-for="d in week.days" :key="d.date" scope="col" :class="{'is-today':d.is_today}">{{d.weekday}}<small>{{d.date.slice(5)}}</small></th></tr></thead>
        <tbody><tr v-for="p in week.periods" :key="p.period_no" :class="{'section-end':p.period_no%2===0}">
          <th scope="row">第 {{p.period_no}} 讲<small>{{p.start_at}}–{{p.end_at}}</small></th>
          <td v-for="d in week.days" :key="d.date"><button class="duty-slot" :class="{'is-selected':selected?.date===d.date&&selected?.period===p.period_no}" :disabled="busy" :aria-label="d.date+'第'+p.period_no+'讲，空闲'+d.periods[p.period_no-1].free_count+'人'" @click="selectSlot(d,p)">
            <strong>空闲 {{d.periods[p.period_no-1].free_count}} 人</strong>
            <span class="duty-free-names">{{d.periods[p.period_no-1].free_members.map(m=>m.name).join('、') || '无可确认空闲学生'}}</span>
            <span class="duty-assigned-names">{{assignedAt(d.date,p.period_no).length ? '值班：'+assignedAt(d.date,p.period_no).map(a=>a.name).join('、') : '未安排'}}</span>
          </button></td>
        </tr></tbody></table>
        <p v-if="!term" class="mg-empty">请在下方配置当前学期，然后查看空闲课表。</p>
      </div>
    </article>

    <article class="mg-surface term-panel">
      <div class="mg-section-title">
        <h2>学期配置</h2>
        <span v-if="term">当前学期：{{term.name}}（{{term.start_date}} 至 {{term.end_date}}）</span>
        <span v-else class="term-missing">尚未配置学期</span>
      </div>
      <p class="mg-muted">
        学期起止日期是"第几周"的基准：成员手动添加课程时要按"第 N 周"选择周次，ICS 导入的课也会换算成周次。
        <strong>开始日期必须是周一</strong>，否则周次会整体错位。
        <span v-if="!term">目前还没有学期，成员端无法手动添加课程。</span>
      </p>
      <div class="mg-form-grid three">
        <label>学期名称<input v-model="termForm.name" placeholder="例如：2026-2027 秋季学期" maxlength="50"></label>
        <label>开始日期（周一）<input type="date" v-model="termForm.startDate" :disabled="busy"></label>
        <label>结束日期<input type="date" v-model="termForm.endDate" :disabled="busy"></label>
      </div>
      <div class="mg-form-footer">
        <button class="mg-button primary" :disabled="busy||!termForm.name||!termForm.startDate||!termForm.endDate" @click="saveTerm">保存并设为当前学期</button>
      </div>

      <div class="mg-section-title term-list-title"><h2>已有学期</h2><span>共 {{allTerms.length}} 个；只能有一个当前学期</span></div>
      <div class="mg-table-wrap"><table class="mg-table">
        <thead><tr><th>学期</th><th>起止日期</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in allTerms" :key="row.id">
            <td><b>{{row.name}}</b></td>
            <td>{{row.start_date}} 至 {{row.end_date}}</td>
            <td><span class="mg-status" :data-status="row.is_current?'APPROVED':'CLOSED'">{{row.is_current?'当前学期':'非当前'}}</span></td>
            <td>
              <button class="mg-link" :disabled="busy||row.is_current" @click="useTerm(row)">设为当前</button>
              <button class="mg-link danger" :disabled="busy||allTerms.length<=1" @click="removeTerm(row)">删除</button>
              <small v-if="allTerms.length<=1">唯一的学期不能删除</small>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="!allTerms.length" class="mg-empty">还没有配置学期</p></div>
      <p class="mg-muted">删除学期会同时删除该学期内所有成员的课程与上课时间，不可恢复。只想换当前学期请用「设为当前」。</p>
    </article>

    <article class="mg-surface">
      <div class="mg-section-title"><h2>自动生成排班</h2><span>重新生成会覆盖区间内自动排定的记录，手工指派会保留</span></div>
      <div class="mg-filters">
        <label>开始日期<input type="date" v-model="from" :disabled="busy"></label>
        <label>结束日期<input type="date" v-model="to" :disabled="busy"></label>
        <button class="mg-button primary" :disabled="busy" @click="generate">生成排班</button>
      </div>
      <p class="mg-muted">排班依据是每位成员的本学期课表。<strong>未添加有效课表的成员不参与排班</strong>，学期结束后旧课表不再作为空闲依据。请提醒成员导入本学期 .ics 文件或手动添加真实课程。</p>
    </article>

    <article class="mg-surface">
      <div class="mg-section-title"><h2>值班次数统计</h2><span>用于核对是否公平（当前区间：最多 {{maxLoad}} 次 / 最少 {{minLoad}} 次）</span></div>
      <div class="mg-filters"><label>统计起<input type="date" v-model="from" :disabled="busy"></label><label>统计止<input type="date" v-model="to" :disabled="busy"></label><button class="mg-button" :disabled="busy" @click="loadFairness">重新统计</button></div>
      <div class="mg-table-wrap"><table class="mg-table"><thead><tr><th>成员</th><th>学号</th><th>值班次数</th></tr></thead><tbody>
        <tr v-for="row in load" :key="row.user_id"><td><b>{{row.name}}</b></td><td>{{row.student_no}}</td><td>{{row.duty_count}}</td></tr>
      </tbody></table><p v-if="!load.length" class="mg-empty">暂无数据</p></div>
    </article>
  </section>`
};
