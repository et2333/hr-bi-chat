<template>
  <section class="attribution-panel" aria-label="离职人数变化分析">
    <a-button v-if="!opened && !task" size="small" data-testid="open-analysis" :loading="busy" @click="open">
      分析变化
    </a-button>

    <div v-if="opened || task" class="analysis-body">
      <h3>离职人数变化分析</h3>
      <p class="muted">按离职事件所属部门拆解数量变化，帮助确定后续核查范围。</p>
      <p v-if="error" role="alert" class="analysis-error">{{ error }}</p>

      <AnalysisForm
        v-if="context && !task"
        :scope="context"
        :busy="busy"
        @confirm="confirm"
        @collapse="opened = false"
      />

      <template v-if="task">
        <AnalysisResultView :task="task" :stages="stages" />
        <a-space>
          <a-button v-if="task.status === 'RUNNING'" :loading="cancelling" @click="cancel">取消分析</a-button>
          <a-button v-else :loading="busy" @click="chooseAgain">重新选择基期</a-button>
        </a-space>
      </template>
      <a-button v-if="disconnected" :loading="busy" data-testid="restore-analysis" @click="restore">恢复查看</a-button>
      <a-button v-if="!context && !task && !disconnected" :loading="busy" @click="prepare">重新读取分析范围</a-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { AnalysisPeriod } from '@/api/attribution'
import { useAttribution } from '@/composables/useAttribution'
import AnalysisForm from '@/components/AnalysisForm.vue'
import AnalysisResultView from '@/components/AnalysisResultView.vue'

const props = defineProps<{ sourceAskId: string; autoOpen?: boolean }>()
const { context, task, stages, busy, cancelling, disconnected, error, prepare, start, restore, cancel, chooseAgain } = useAttribution(props.sourceAskId)
const opened = ref(false)

onMounted(async () => { await restore(); if (props.autoOpen && !task.value) await open() })
async function open() { opened.value = true; await prepare() }
async function confirm(baseline: AnalysisPeriod) { await start(baseline, 'dual') }
</script>

<style scoped>
.attribution-panel { margin-top: 16px; }
.analysis-body { padding: 16px; background: #fafcff; border: 1px solid #d9e6f5; border-radius: 8px; }
h3 { margin: 0 0 8px; font-size: 16px; }
.muted { font-size: 12px; color: #657386; overflow-wrap: anywhere; }
.analysis-error { color: #b42318; }
</style>
