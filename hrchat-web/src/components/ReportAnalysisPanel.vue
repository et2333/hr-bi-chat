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
      <a-button v-if="!context && !task && !disconnected" :loading="busy" @click="prepare">重新读取分析范围</a-button>
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
}>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const { context, task, stages, busy, cancelling, disconnected, error, prepare, start, restore, cancel, chooseAgain } =
  useReportAnalysis(() => ({ reportId: props.reportId, dataVersionId: props.dataVersionId, capabilityId: props.capabilityId }))

const panelTitle = '进一步分析'
const versionLabel = computed(() => String(task.value?.context.sourceDataVersionId ?? props.dataVersionId))
const scopeLabel = computed(() => task.value?.context.scopeLabel ?? context.value?.scopeLabel ?? '报表授权范围')

/** 窄屏使用全屏面板，宽屏使用侧边面板。 */
const isNarrow = ref(false)
const drawerWidth = computed(() => (isNarrow.value ? '100%' : 520))
function syncWidth() { isNarrow.value = typeof window !== 'undefined' && window.innerWidth < 768 }
onMounted(() => { syncWidth(); window.addEventListener('resize', syncWidth) })
onBeforeUnmount(() => window.removeEventListener('resize', syncWidth))

/** 打开时按任务 ID 恢复；无任务才读取预填范围。收起仅停止展示，不取消任务。 */
watch(() => props.open, async (visible) => {
  if (!visible) return
  if (task.value) return
  await restore()
  if (!task.value && !context.value) await prepare()
}, { immediate: true })

function collapse() { emit('update:open', false) }
async function confirm(baseline: AnalysisPeriod) { await start(baseline, 'dual') }
</script>

<style scoped>
.report-analysis-panel .muted { font-size: 12px; color: #657386; overflow-wrap: anywhere; }
.analysis-error { color: #b42318; }
</style>
