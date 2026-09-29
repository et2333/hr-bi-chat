<template>
  <a-modal
    :open="open"
    :title="mode === 'create' ? '新建指标' : `编辑指标：${detail?.name ?? ''}`"
    :confirm-loading="saving"
    width="640px"
    @update:open="(v: boolean) => emit('update:open', v)"
    @ok="submit"
  >
    <a-alert
      v-if="mode === 'edit'"
      class="editor-tip"
      type="warning"
      show-icon
      message="修改口径说明或计算公式将生成待审批版本，审批通过前线上仍使用旧口径；名称/周期/方向/维度修改即时生效"
    />
    <a-form ref="formRef" :model="form" :rules="rules" :label-col="{ span: 6 }" :wrapper-col="{ span: 18 }">
      <a-form-item label="指标编码" name="code">
        <a-input v-model:value="form.code" :disabled="mode === 'edit'" placeholder="如 offer_accept_rate" />
      </a-form-item>
      <a-form-item label="指标名称" name="name">
        <a-input v-model:value="form.name" placeholder="如 offer 接受率" />
      </a-form-item>
      <a-form-item label="主题域" name="domain">
        <a-select v-model:value="form.domain" :disabled="mode === 'edit'" placeholder="选择主题域">
          <a-select-option v-for="d in DOMAIN_OPTIONS" :key="d.value" :value="d.value">{{ d.label }}</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="口径说明" name="definition">
        <a-textarea v-model:value="form.definition" :rows="2" placeholder="如：口径：offer 接受人数 ÷ 发放 offer 总数" />
      </a-form-item>
      <a-form-item label="计算公式" name="formula">
        <a-textarea v-model:value="form.formula" :rows="3" placeholder="SQL 模板或派生公式，如 accept_count / offer_count" />
      </a-form-item>
      <a-form-item label="默认周期" name="defaultGrain">
        <a-select v-model:value="form.defaultGrain">
          <a-select-option v-for="g in GRAIN_OPTIONS" :key="g" :value="g">{{ g }}</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="指标方向" name="goodDirection">
        <a-radio-group v-model:value="form.goodDirection">
          <a-radio :value="1">正向（越大越好）</a-radio>
          <a-radio :value="-1">逆向（越小越好）</a-radio>
        </a-radio-group>
      </a-form-item>
      <a-form-item label="敏感指标" name="sensitive">
        <a-switch v-model:checked="form.sensitive" checked-children="敏感" un-checked-children="公开" />
      </a-form-item>
      <a-form-item label="可用维度" name="availableDimensions">
        <a-select
          v-model:value="form.availableDimensions"
          mode="multiple"
          allow-clear
          placeholder="选择该指标可分析的维度"
          :options="dimOptions"
        />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { FormInstance } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { semanticApi } from '@/api'
import type { DimensionSummary, MetricDetail } from '@/api/semantic'

const props = defineProps<{
  open: boolean
  mode: 'create' | 'edit'
  detail?: MetricDetail | null
  dimensions: DimensionSummary[]
}>()

const emit = defineEmits<{
  'update:open': [v: boolean]
  saved: []
}>()

const DOMAIN_OPTIONS = [
  { value: 'staff', label: '人员' },
  { value: 'pay', label: '薪酬' },
  { value: 'attendance', label: '考勤' },
  { value: 'perf', label: '绩效' },
  { value: 'recruit', label: '招聘' },
  { value: 'org', label: '组织' },
]
const GRAIN_OPTIONS = ['DAY', 'WEEK', 'MONTH', 'QUARTER', 'YEAR']

const formRef = ref<FormInstance>()
const saving = ref(false)

interface MetricForm {
  code: string
  name: string
  domain: string
  definition: string
  formula: string
  defaultGrain: string
  goodDirection: number
  sensitive: boolean
  availableDimensions: string[]
}

const emptyForm = (): MetricForm => ({
  code: '',
  name: '',
  domain: 'staff',
  definition: '',
  formula: '',
  defaultGrain: 'MONTH',
  goodDirection: 1,
  sensitive: false,
  availableDimensions: [],
})

const form = reactive<MetricForm>(emptyForm())

const dimOptions = computed(() => props.dimensions.map((d) => ({ value: d.code, label: `${d.name}（${d.code}）` })))

const rules = {
  code: [{ required: true, message: '请填写指标编码', trigger: 'blur' }],
  name: [{ required: true, message: '请填写指标名称', trigger: 'blur' }],
  domain: [{ required: true, message: '请选择主题域', trigger: 'change' }],
  definition: [{ required: true, message: '请填写口径说明', trigger: 'blur' }],
  formula: [{ required: true, message: '请填写计算公式', trigger: 'blur' }],
  defaultGrain: [{ required: true, message: '请选择默认周期', trigger: 'change' }],
}

// 每次打开时按模式填充表单
watch(
  () => props.open,
  (open) => {
    if (!open) return
    if (props.mode === 'edit' && props.detail) {
      const d = props.detail
      Object.assign(form, {
        code: d.code,
        name: d.name,
        domain: d.domain,
        definition: d.calcScope,
        formula: d.formulaExpr,
        defaultGrain: d.defaultPeriod,
        goodDirection: d.goodDirection,
        sensitive: d.permLevel === 3,
        availableDimensions: [...d.availableDimensions],
      })
    } else {
      Object.assign(form, emptyForm())
    }
    formRef.value?.clearValidate()
  },
)

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    if (props.mode === 'create') {
      await semanticApi.createMetric({
        name: form.name.trim(),
        code: form.code.trim(),
        domain: form.domain,
        definition: form.definition.trim(),
        formula: form.formula.trim(),
        defaultGrain: form.defaultGrain,
        availableDimensions: form.availableDimensions,
        sensitive: form.sensitive,
      })
      message.success('指标已创建并生效')
    } else if (props.detail) {
      const old = props.detail
      const caliberChanged =
        form.definition.trim() !== old.calcScope || form.formula.trim() !== old.formulaExpr
      await semanticApi.patchMetric(old.id, {
        name: form.name.trim(),
        definition: form.definition.trim(),
        formula: form.formula.trim(),
        defaultGrain: form.defaultGrain,
        goodDirection: form.goodDirection,
        sensitive: form.sensitive,
        availableDimensions: form.availableDimensions,
      })
      message.success(caliberChanged ? '修改已保存，口径变更待审批' : '指标已更新')
    }
    emit('update:open', false)
    emit('saved')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.editor-tip {
  margin-bottom: 16px;
}
</style>
