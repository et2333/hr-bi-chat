<template>
  <a-modal
    :open="open"
    title="版本对比"
    width="720px"
    :footer="null"
    @update:open="(v: boolean) => emit('update:open', v)"
  >
    <div class="diff-selectors">
      <a-select v-model:value="leftNo" :options="versionOptions" class="version-select" />
      <span class="diff-arrow">对比</span>
      <a-select v-model:value="rightNo" :options="versionOptions" class="version-select" />
    </div>

    <div v-if="left && right" class="diff-body">
      <div class="diff-section">
        <div class="section-title">口径变化</div>
        <div class="diff-lines">
          <div
            v-for="(row, i) in diffLines(left.calcScope ?? '', right.calcScope ?? '')"
            :key="`c${i}`"
            :class="['diff-line', row.type]"
          >
            <span class="diff-mark">{{ mark(row.type) }}</span>{{ row.text || '（空）' }}
          </div>
        </div>
      </div>
      <div class="diff-section">
        <div class="section-title">公式变化</div>
        <div class="diff-lines">
          <div
            v-for="(row, i) in diffLines(left.formulaExpr, right.formulaExpr)"
            :key="`f${i}`"
            :class="['diff-line', row.type]"
          >
            <span class="diff-mark">{{ mark(row.type) }}</span>{{ row.text || '（空）' }}
          </div>
        </div>
      </div>
    </div>
    <a-empty v-else description="请选择两个版本" />
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { MetricVersionInfo } from '@/api/semantic'

interface DiffRow {
  type: 'same' | 'add' | 'del'
  text: string
}

const props = defineProps<{ open: boolean; versions: MetricVersionInfo[] }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()

const leftNo = ref<number>()
const rightNo = ref<number>()

const versionOptions = computed(() =>
  [...props.versions]
    .sort((a, b) => b.versionNo - a.versionNo)
    .map((v) => ({ value: v.versionNo, label: `v${v.versionNo}` })),
)

const left = computed(() => props.versions.find((v) => v.versionNo === leftNo.value))
const right = computed(() => props.versions.find((v) => v.versionNo === rightNo.value))

// 打开时默认选中最近两个版本（左旧右新）
watch(
  () => props.open,
  (v) => {
    if (v && versionOptions.value.length >= 2) {
      leftNo.value = versionOptions.value[1].value
      rightNo.value = versionOptions.value[0].value
    }
  },
)

function mark(type: DiffRow['type']): string {
  return type === 'add' ? '+' : type === 'del' ? '-' : ' '
}

/** 行级 LCS diff（纯前端，无第三方依赖）。 */
function diffLines(a: string, b: string): DiffRow[] {
  const x = a.split('\n')
  const y = b.split('\n')
  const m = x.length
  const n = y.length
  // dp[i][j] = x[i:] 与 y[j:] 的最长公共长度
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0))
  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      dp[i][j] = x[i] === y[j]
        ? dp[i + 1][j + 1] + 1
        : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const rows: DiffRow[] = []
  let i = 0
  let j = 0
  while (i < m && j < n) {
    if (x[i] === y[j]) {
      rows.push({ type: 'same', text: x[i] })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      rows.push({ type: 'del', text: x[i] })
      i++
    } else {
      rows.push({ type: 'add', text: y[j] })
      j++
    }
  }
  while (i < m) rows.push({ type: 'del', text: x[i++] })
  while (j < n) rows.push({ type: 'add', text: y[j++] })
  return rows
}
</script>

<style scoped>
.diff-selectors {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.version-select {
  flex: 1;
}

.diff-arrow {
  color: rgba(0, 0, 0, 0.45);
  font-size: 13px;
}

.diff-section + .diff-section {
  margin-top: 16px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.65);
  margin-bottom: 6px;
}

.diff-lines {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  overflow: hidden;
}

.diff-line {
  font-size: 12px;
  line-height: 1.8;
  padding: 0 8px;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.diff-line.same {
  color: rgba(0, 0, 0, 0.75);
}

.diff-line.del {
  background: #fff1f0;
  color: #cf1322;
}

.diff-line.add {
  background: #f6ffed;
  color: #389e0d;
}

.diff-mark {
  display: inline-block;
  width: 14px;
  user-select: none;
}
</style>
