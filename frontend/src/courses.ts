import { computed, onMounted, ref } from 'vue';
import './management.css';

/**
 * 我的课程表：周课表网格 + ICS 导入 + 手动添加/编辑。
 *
 * 两条关键语义（与后端一致）：
 *   1. 一个成员一个学期只有一份课表，导入的和手动添加的都在里面，**都可以修改**；
 *   2. 重新导入 ICS 只同步 ICS 来源的课，手动添加的课不受影响 —— 因此导入不会
 *      再"清空整份课表"，被移除的课程名会明确列出来告诉用户。
 *
 * "第几周"以学期起始日为基准，所以未配置学期时无法手动添加课程。
 */
export default {
  props: ['request'],
  setup(props: any) {
    const week = ref<any>({ periods: [], days: [], duty: [] });
    const courses = ref<any[]>([]);
    const term = ref<any>(null);
    const weekOffset = ref(0);
    const busy = ref(false);
    const error = ref('');
    const message = ref('');
    const warnings = ref<string[]>([]);
    const removedNames = ref<string[]>([]);
    const fileInput = ref<HTMLInputElement | null>(null);

    // 编辑器状态
    const editing = ref(false);
    const editingId = ref<number | null>(null);
    const draft = ref<any>(blankDraft());

    const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    const periodOptions = computed(() => week.value.periods || []);

    function blankDraft() {
      return {
        summary: '',
        location: '',
        meetings: [newMeeting()]
      };
    }

    function newMeeting() {
      return { weekday: 0, periodStart: 1, periodEnd: 2, weeks: [] as number[], weekRangeFrom: 1, weekRangeTo: 16 };
    }

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

    const loadAll = async () => {
      const terms = await props.request('/courses/terms');
      term.value = terms.current;
      const own = await props.request('/courses/my');
      courses.value = own.courses;
      week.value = await props.request('/courses/week?week=' + weekOffset.value);
    };

    const refresh = () => run(loadAll);
    const goWeek = (delta: number) => run(async () => { weekOffset.value += delta; await loadAll(); });
    const thisWeek = () => run(async () => { weekOffset.value = 0; await loadAll(); });

    /** 导入 ICS：不再"清空整份课表"，被移除的课程会列出来。 */
    const importFile = (event: Event) => {
      const input = event.target as HTMLInputElement;
      const file = input.files && input.files[0];
      if (!file) return;
      run(async () => {
        warnings.value = [];
        removedNames.value = [];
        const text = await file.text();
        const result = await props.request('/courses/import', {
          method: 'POST',
          body: text,
          headers: { 'Content-Type': 'text/calendar' }
        });
        message.value = result.message;
        warnings.value = result.warnings || [];
        removedNames.value = result.removed_course_names || [];
        await loadAll();
      });
      input.value = '';
    };

    // -------------------------------------------------------------------------
    // 编辑器
    // -------------------------------------------------------------------------

    const openNewCourse = () => {
      editingId.value = null;
      draft.value = blankDraft();
      editing.value = true;
      error.value = '';
    };

    /** 从已有课程进入编辑（导入的课也能改）。 */
    const openEditCourse = (course: any) => {
      editingId.value = Number(course.id);
      draft.value = {
        summary: course.summary || '',
        location: course.location || '',
        meetings: (course.meetings || []).map((m: any) => ({
          weekday: Number(m.weekday),
          periodStart: Number(m.period_start),
          periodEnd: Number(m.period_end),
          weeks: Array.isArray(m.weeks) ? m.weeks.map(Number) : [],
          weekRangeFrom: 1,
          weekRangeTo: 16
        }))
      };
      if (!draft.value.meetings.length) draft.value.meetings = [newMeeting()];
      editing.value = true;
      error.value = '';
    };

    const cancelEdit = () => {
      editing.value = false;
      editingId.value = null;
      draft.value = blankDraft();
    };

    const addMeeting = () => draft.value.meetings.push(newMeeting());
    const removeMeeting = (index: number) => {
      draft.value.meetings.splice(index, 1);
      if (!draft.value.meetings.length) draft.value.meetings.push(newMeeting());
    };

    /** 把"起止周"换算成周次数组；勾选单双周时再筛一遍。 */
    const applyRange = (meeting: any, parity?: 'odd' | 'even') => {
      const from = Math.max(1, Number(meeting.weekRangeFrom) || 1);
      const to = Math.max(from, Number(meeting.weekRangeTo) || from);
      const weeks: number[] = [];
      for (let w = from; w <= to; w++) {
        if (parity === 'odd' && w % 2 === 0) continue;
        if (parity === 'even' && w % 2 === 1) continue;
        weeks.push(w);
      }
      meeting.weeks = weeks;
    };

    const toggleWeek = (meeting: any, w: number) => {
      const index = meeting.weeks.indexOf(w);
      if (index >= 0) meeting.weeks.splice(index, 1);
      else meeting.weeks.push(w);
      meeting.weeks.sort((a: number, b: number) => a - b);
    };

    const weeksSummary = (meeting: any) => {
      const weeks = [...(meeting.weeks || [])].sort((a: number, b: number) => a - b);
      if (!weeks.length) return '未选择周次';
      return '第 ' + weeks.join('、') + ' 周';
    };

    const save = () =>
      run(async () => {
        if (!draft.value.summary.trim()) throw new Error('请填写课程名称');
        const meetings = draft.value.meetings.map((m: any) => ({
          weekday: Number(m.weekday),
          periodStart: Number(m.periodStart),
          periodEnd: Number(m.periodEnd),
          weeks: [...(m.weeks || [])].map(Number)
        }));
        if (!meetings.length) throw new Error('请至少添加一条上课时间');
        for (const m of meetings) {
          if (!m.weeks.length) throw new Error('每条上课时间都要选择周次');
          if (m.periodEnd < m.periodStart) throw new Error('结束讲课不能早于开始讲课');
        }
        const result = await props.request('/courses/save', {
          method: 'POST',
          body: JSON.stringify({
            courseId: editingId.value,
            summary: draft.value.summary,
            location: draft.value.location,
            meetings
          })
        });
        message.value = result.message;
        editing.value = false;
        editingId.value = null;
        await loadAll();
      });

    const removeCourse = (course: any) => {
      if (!window.confirm('确认删除课程「' + course.summary + '」及其全部上课时间？')) return;
      run(async () => {
        const result = await props.request('/courses/' + course.id + '/delete', { method: 'POST' });
        message.value = result.message;
        await loadAll();
      });
    };

    // -------------------------------------------------------------------------
    // 周课表网格
    // -------------------------------------------------------------------------

    const dutyMap = computed(() => {
      const map: Record<string, any> = {};
      for (const item of week.value.duty || []) map[item.date + '|' + item.period_no] = item;
      return map;
    });
    const dutyAt = (day: any, periodNo: number) => dutyMap.value[day.date + '|' + periodNo];

    const periodTime = (periodNo: number) => {
      const found = (week.value.periods || []).find((p: any) => p.period_no === periodNo);
      return found ? found.start_at + '-' + found.end_at : '';
    };
    const isSectionEnd = (periodNo: number) => periodNo % 2 === 0;

    const weekCourseCount = computed(() => {
      let count = 0;
      for (const day of week.value.days || []) {
        for (const cell of day.periods || []) if (cell.busy) count++;
      }
      return count;
    });

    const termWeeks = computed(() => {
      if (!term.value) return 0;
      const start = new Date(term.value.start_date + 'T00:00');
      const end = new Date(term.value.end_date + 'T00:00');
      return Math.floor((end.getTime() - start.getTime()) / (7 * 86400000)) + 1;
    });

    /** 当前显示的这一周是学期第几周（用于表头提示）。 */
    const shownWeekNumber = computed(() => {
      const days = week.value.days || [];
      return days.length ? days[0].week_number : 0;
    });

    const hasImported = computed(() => week.value.has_course_data === true);

    onMounted(refresh);

    return {
      week, courses, term, weekOffset, busy, error, message, warnings, removedNames, fileInput,
      editing, editingId, draft, weekdays, periodOptions,
      refresh, goWeek, thisWeek, importFile,
      openNewCourse, openEditCourse, cancelEdit, addMeeting, removeMeeting,
      applyRange, toggleWeek, weeksSummary, save, removeCourse,
      dutyAt, periodTime, isSectionEnd, weekCourseCount, termWeeks, shownWeekNumber, hasImported
    };
  },
  template: `<section class="management courses-page" :aria-busy="busy">
    <header class="mg-heading">
      <div><p class="mg-kicker">实验室 / 个人课表</p><h1>我的课程表</h1>
        <p v-if="term">当前学期：{{term.name}}（{{term.start_date}} 起，共 {{termWeeks}} 周）。课表仅你本人与管理员可见，用于安排值班。</p>
        <p v-else>还没有配置学期，因此无法按"第几周"添加课程。请让管理员先在「值班排班」页配置本学期起止日期。</p>
      </div>
      <div class="heading-actions">
        <label class="mg-button primary file-button" :class="{disabled:busy||!term}">导入 / 更新 .ics<input ref="fileInput" type="file" accept=".ics,text/calendar" :disabled="busy||!term" @change="importFile"></label>
        <button class="mg-button" :disabled="busy||!term" @click="openNewCourse">＋ 手动添加课程</button>
      </div>
    </header>

    <p v-if="error" class="mg-alert" role="alert">{{error}}</p>
    <p v-if="message" class="mg-notice" role="status">{{message}}</p>
    <div v-if="removedNames.length" class="mg-alert" role="alert">
      <b>本次导入移除了 {{removedNames.length}} 门课程</b>（文件里已不存在，仅限 ICS 来源）：{{removedNames.join('、')}}
      <p>手动添加的课程不受导入影响。如果这些课不该消失，说明导入的文件不完整，请重新导入完整课表。</p>
    </div>
    <div v-if="warnings.length" class="mg-alert" role="alert"><b>导入时发现以下情况，请核对：</b><ul><li v-for="w in warnings" :key="w">{{w}}</li></ul></div>
    <p v-if="term && !hasImported" class="mg-notice">你尚未添加有效的本学期课表，或当前学期已经结束。有效学期内可以导入 .ics，也可以点「手动添加课程」逐个填写。没有有效课表时<strong>不会被视为空闲或参与排班</strong>。</p>

    <!-- 课程编辑器 -->
    <article v-if="editing" class="mg-surface course-editor">
      <div class="mg-section-title">
        <h2>{{editingId?'编辑课程':'手动添加课程'}}</h2>
        <span>一门课可以有多条上课时间：不同周几、不同讲课、不同周次</span>
      </div>
      <div class="mg-form-grid two">
        <label>课程名称<input v-model="draft.summary" maxlength="100" placeholder="例如：操作系统C"></label>
        <label>上课地点（可选）<input v-model="draft.location" maxlength="100" placeholder="例如：HE-405"></label>
      </div>

      <div v-for="(m,index) in draft.meetings" :key="index" class="meeting-card">
        <div class="meeting-head">
          <b>上课时间 {{index+1}}</b>
          <button class="mg-link danger" :disabled="busy" @click="removeMeeting(index)">删除这条</button>
        </div>
        <div class="mg-form-grid three">
          <label>星期
            <select v-model.number="m.weekday">
              <option v-for="(label,wi) in weekdays" :key="wi" :value="wi">{{label}}</option>
            </select>
          </label>
          <label>开始讲课
            <select v-model.number="m.periodStart">
              <option v-for="p in periodOptions" :key="p.period_no" :value="p.period_no">第{{p.period_no}}讲课 {{p.start_at}}</option>
            </select>
          </label>
          <label>结束讲课
            <select v-model.number="m.periodEnd">
              <option v-for="p in periodOptions" :key="p.period_no" :value="p.period_no">第{{p.period_no}}讲课 {{p.end_at}}</option>
            </select>
          </label>
        </div>
        <div class="week-picker">
          <div class="week-range">
            <label>第<input type="number" min="1" :max="termWeeks" v-model.number="m.weekRangeFrom"> 周 到
              第<input type="number" min="1" :max="termWeeks" v-model.number="m.weekRangeTo"> 周</label>
            <button class="mg-button" :disabled="busy" @click="applyRange(m)">应用</button>
            <button class="mg-button" :disabled="busy" @click="applyRange(m,'odd')">单周</button>
            <button class="mg-button" :disabled="busy" @click="applyRange(m,'even')">双周</button>
          </div>
          <div class="week-chips">
            <button v-for="w in termWeeks" :key="w" type="button"
                    class="week-chip" :class="{on:m.weeks.includes(w)}"
                    :disabled="busy" @click="toggleWeek(m,w)">{{w}}</button>
          </div>
          <p class="mg-muted">已选：{{weeksSummary(m)}}</p>
        </div>
      </div>

      <footer class="mg-form-footer">
        <button class="mg-button" :disabled="busy" @click="addMeeting">＋ 再添加一条上课时间</button>
        <button class="mg-button" :disabled="busy" @click="cancelEdit">取消</button>
        <button class="mg-button primary" :disabled="busy" @click="save">{{editingId?'保存修改':'添加课程'}}</button>
      </footer>
    </article>

    <!-- 周课表 -->
    <article class="mg-surface">
      <div class="mg-section-title">
        <h2>周课表</h2>
        <span v-if="shownWeekNumber>0">学期第 {{shownWeekNumber}} 周 · {{week.week_label}} · {{weekCourseCount}} 个上课时段<span v-if="week.duty.length">，{{week.duty.length}} 次值班</span></span>
      </div>
      <div class="week-nav">
        <button class="mg-button" :disabled="busy" @click="goWeek(-1)">← 上一周</button>
        <button class="mg-button" :disabled="busy||weekOffset===0" @click="thisWeek">回到本周</button>
        <button class="mg-button" :disabled="busy" @click="goWeek(1)">下一周 →</button>
      </div>
      <div class="week-grid-wrap">
        <table class="week-grid">
          <colgroup>
            <col class="week-col-period">
            <col v-for="day in week.days" :key="day.date" class="week-col-day">
          </colgroup>
          <thead>
            <tr>
              <th class="week-corner">讲课</th>
              <th v-for="day in week.days" :key="day.date" :class="{today:day.is_today}">
                <b>{{day.weekday}}</b><small>{{day.date.slice(5)}}</small>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="periodNo in 10" :key="periodNo" :class="{'section-end':isSectionEnd(periodNo)}">
              <th class="week-period"><b>第 {{periodNo}} 讲课</b><small>{{periodTime(periodNo)}}</small></th>
              <td v-for="day in week.days" :key="day.date + '-' + periodNo" :class="['week-cell',{today:day.is_today}]">
                <template v-for="cell in [day.periods[periodNo-1]]" :key="periodNo">
                  <div v-if="cell && cell.busy" class="course-block">
                    <b>{{cell.courses[0].summary}}</b>
                    <small v-if="cell.courses[0].location">{{cell.courses[0].location}}</small>
                  </div>
                  <div v-else-if="dutyAt(day, periodNo)" class="duty-block">
                    <b>值班</b><small>{{dutyAt(day, periodNo).source==='MANUAL'?'手动安排':'自动排班'}}</small>
                  </div>
                  <div v-else class="free-block">—</div>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="mg-muted week-legend">
        <span class="legend-item"><i class="legend-course"></i>有课</span>
        <span class="legend-item"><i class="legend-duty"></i>值班</span>
        <span class="legend-item"><i class="legend-free"></i>空闲</span>
        <span>共 {{week.periods.length}} 个讲课；两讲课为一节，两讲课之间休息 5 分钟，两节之间休息 20 分钟。周六周日同样可以排课。</span>
      </p>
    </article>

    <!-- 课程清单（含编辑入口） -->
    <article class="mg-surface">
      <div class="mg-section-title"><h2>课程清单</h2><span>{{courses.length}} 门课程 · 导入的课也可以修改</span></div>
      <div class="mg-table-wrap"><table class="mg-table">
        <thead><tr><th>课程</th><th>来源</th><th>上课时间</th><th>地点</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="c in courses" :key="c.id">
            <td><b>{{c.summary}}</b></td>
            <td><span class="mg-status" :data-status="c.source==='MANUAL'?'PENDING':'APPROVED'">{{c.source==='MANUAL'?'手动添加':'ICS 导入'}}</span></td>
            <td>
              <div v-for="m in c.meetings" :key="m.id" class="meeting-line">
                {{m.weekday_label}} 第{{m.period_start}}-{{m.period_end}}讲课 · {{weeksSummary(m)}}
              </div>
              <span v-if="!c.meetings || !c.meetings.length" class="mg-muted">—</span>
            </td>
            <td>{{c.location||'—'}}</td>
            <td>
              <button class="mg-link" :disabled="busy||!term" @click="openEditCourse(c)">编辑</button>
              <button class="mg-link danger" :disabled="busy" @click="removeCourse(c)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="!courses.length" class="mg-empty">暂无课程。可以导入 .ics 文件，或点右上角「手动添加课程」。</p></div>
    </article>
  </section>`
};
