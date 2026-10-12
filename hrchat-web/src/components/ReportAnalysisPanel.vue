<template>
  <a-drawer
    :open="open"
    :width="drawerWidth"
    placement="right"
    :title="panelTitle"
    :body-style="{ padding: '16px' }"
    @close="collapse"
  >
    <section class="report-analysis-panel" aria-label="进一步分析">
      <p class="muted">
        来源报表版本：{{ versionLabel }} · 范围：{{ scopeLabel }}
      </p>
      <p class="muted">结果独立于基础洞察，不会改写报表；收起不会取消分析，重新打开按任务恢复。</p>
      <p v-if="error" role="alert" class="analysis-error">{{ error }}</p>

      <a-skeleton v-if="!context && !task && !disconnected" active :paragraph="{ rows: 3 }" />

      <AnalysisForm
        v-if="context && !task"
        :scope="context"
        :busy="busy"
        current-label="本期（报表口径）"
        :report-meta="{ supported: context.supported, executable: context.executable, missingParams: context.missingParams }"
        @confirm="confirm"
        @collapse="collapse"
      />

      <template v-if="task">
        <AnalysisResultView :task="task" :stages="stages" />
        <a-space>
          <a-button v-if="task.status === 'RUNNING'" :loading="cancelling" @click="cancel">取消分析</a-button>
          <a-button v-else :loading="busy" @click="chooseAgain">重新选择基期</a-button>
          <a-button @click="collapse">收起</a-button>
        </a-space>
      </template>

      <a-button v-if="disconnected" :loading="busy" data-testid="restore-report-analysis" @click="restore">恢复查看</a-button>
      <a-button v-if="sourceReady && !context && !task && !disconnected" :loading="busy" @click="prepare">重新读取分析范围</a-button>
    </section>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AnalysisPeriod } from '@/api/attribution'
import { useReportAnalysis } from '@/composables/useReportAnalysis'
import AnalysisForm from '@/components/AnalysisForm.vue'
import AnalysisResultView from '@/components/AnalysisResultView.vue'

const props = defineProps<{
  open: boolean
  reportId: number
  dataVersionId: number
  capabilityId: string
  /** A2：服务端动作引用；提供时由后端按原视图/期间恢复来源，不传期间/组织。 */
  componentId?: number
  viewKey?: string
  actionId?: string
}>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

/** 动作引用齐备时使用视图级来源；否则退回版本级来源（兼容旧调用）。 */
const actionRef = computed(() =>
  props.componentId && props.viewKey && props.actionId
    ? { componentId: props.componentId, viewKey: props.viewKey, actionId: props.actionId }
    : null)

const { context, task, stages, busy, cancelling, disconnected, error, prepare, start, restore, cancel, chooseAgain, resetSession } =
  useReportAnalysis(() => ({
    reportId: props.reportId, dataVersionId: props.dataVersionId, capabilityId: props.capabilityId,
    actionRef: actionRef.value,
  }))

/** 来源就绪（有效报表版本 + 能力）才允许读取/建立会话，避免初始空值触发无效请求。 */
const sourceReady = computed(() => props.reportId > 0 && props.dataVersionId > 0 && !!props.capabilityId)
/** 来源身份签名：切换动作/视图即显式选择新的分析会话，避免复用同一恢复键。 */
const sourceSignature = computed(() => {
  const ref = actionRef.value
  return `${props.reportId}:${props.dataVersionId}:${props.capabilityId}`
    + `:${ref?.componentId ?? '-'}:${ref?.viewKey ?? '-'}:${ref?.actionId ?? '-'}`
})

const panelTitle = '进一步分析'
const versionLabel = computed(() => String(task.value?.context.sourceDataVersionId ?? props.dataVersionId))
const scopeLabel = computed(() => task.value?.context.scopeLabel ?? context.value?.scopeLabel ?? '报表授权范围')

/** 窄屏使用全屏面板，宽屏使用侧边面板。 */
const isNarrow = ref(false)
const drawerWidth = computed(() => (isNarrow.value ? '100%' : 520))
function syncWidth() { isNarrow.value = typeof window !== 'undefined' && window.innerWidth < 768 }
onMounted(() => { syncWidth(); window.addEventListener('resize', syncWidth) })
onBeforeUnmount(() => window.removeEventListener('resize', syncWidth))

/**
 * 打开或切换来源时同步会话：来源未就绪直接返回（不建立空会话）；来源身份变化且无运行中任务时
 * 立即重建会话——即使首次 prepare 仍在途、context/task 都还是空，也要让旧来源的在途请求失效，
 * 不能以“是否已有返回数据”作为重置条件；运行中任务不被来源变化篡改。收起仅停止展示，不取消任务。
 */
let sessionSignature = ''
async function sync(open: boolean) {
  if (!open || !sourceReady.value) return
  if (sessionSignature !== sourceSignature.value) {
    if (task.value?.status === 'RUNNING') return
    resetSession()
    sessionSignature = sourceSignature.value
  }
  if (task.value) return
  await restore()
  if (!task.value && !context.value) await prepare()
}

watch([() => props.open, sourceSignature], ([open]) => { void sync(open) }, { immediate: true })

function collapse() { emit('update:open', false) }
async function confirm(baseline: AnalysisPeriod) { await start(baseline, 'dual') }
</script>

<style scoped>
.report-analysis-panel .muted { font-size: 12px; color: #657386; overflow-wrap: anywhere; }
.analysis-error { color: #b42318; }
</style>
