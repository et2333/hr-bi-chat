<template>
  <div class="clarify-card">
    <a-alert type="warning" show-icon class="clarify-banner">
      <template #message>我需要向您确认几个问题，才能给出更准确的答案</template>
    </a-alert>
    <div v-for="(q, qi) in clarify.questions" :key="q.questionId" class="clarify-question">
      <div class="question-title">{{ qi + 1 }}. {{ q.question }}</div>
      <a-checkbox-group
        v-if="q.multiple"
        :value="selected[q.questionId]"
        @change="onGroupChange(q.questionId, true, $event)"
      >
        <a-checkbox v-for="o in q.options" :key="o.optionId" :value="o.optionId">
          {{ o.label }}
        </a-checkbox>
      </a-checkbox-group>
      <a-radio-group
        v-else
        :value="selected[q.questionId]?.[0]"
        @change="onGroupChange(q.questionId, false, $event)"
      >
        <a-radio v-for="o in q.options" :key="o.optionId" :value="o.optionId">
          {{ o.label }}
        </a-radio>
      </a-radio-group>
    </div>
    <p>也可在下方输入框补充条件；输入“取消”结束澄清，输入“重新查询”开始新任务。</p>
    <a-button v-if="clarify.questions.some(q => q.options.length)" type="primary" :disabled="!allAnswered || submitting" :loading="submitting" @click="submit">
      确认回答
    </a-button>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { ClarifyQuestions } from '@/api/types'

const props = defineProps<{
  clarify: ClarifyQuestions
  submitting?: boolean
}>()

const emit = defineEmits<{
  submit: [answers: Array<{ questionId: string; optionIds: string[] }>]
}>()

/** questionId → optionIds[] */
const selected = reactive<Record<string, string[]>>({})
watch(() => JSON.stringify(props.clarify.questions), () => {
  for (const id of Object.keys(selected)) delete selected[id]
})

/** 兼容 ant-design-vue 复选框组（直接给数组）与单选组（事件对象）两种回调形态 */
function onGroupChange(questionId: string, multiple: boolean, payload: unknown) {
  let values: string[] = []
  if (Array.isArray(payload)) {
    values = payload.map(String)
  } else if (payload && typeof payload === 'object' && 'target' in payload) {
    const target = (payload as { target?: { value?: unknown } }).target
    values = target?.value == null ? [] : [String(target.value)]
  }
  selected[questionId] = multiple ? values : values.length > 0 ? [values[0]] : []
}

const allAnswered = computed(() =>
  props.clarify.questions.every((q) => (selected[q.questionId]?.length ?? 0) > 0),
)

function submit() {
  const answers = props.clarify.questions.map((q) => ({
    questionId: q.questionId,
    optionIds: selected[q.questionId] ?? [],
  }))
  emit('submit', answers)
}
</script>

<style scoped>
.clarify-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.clarify-question {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.question-title {
  font-weight: 600;
}
</style>
