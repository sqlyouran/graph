import {
  AlertCircle,
  ArrowRight,
  CheckCircle2,
  Clock3,
  Compass,
  MapPinned,
  Route,
  Sparkles,
} from "lucide-react";
import { FormEvent, useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";

export const USE_MOCK = false;

const MOCK_DELAY_MS = 1_500;
const MOCK_MODEL = "qwen3.8-max";
const MOCK_TRIP = `## 杭州三日轻旅行

### 第 1 天 · 西湖与老城

- **上午**：从断桥出发，沿白堤步行至孤山，慢慢看西湖晨景。
- **午餐**：在湖滨一带品尝杭帮菜，人均约 120 元。
- **下午**：乘船游览三潭印月，傍晚前往雷峰塔。
- **晚上**：漫步南宋御街与河坊街，尝一份葱包桧。

### 第 2 天 · 灵隐与茶山

- **上午**：提前前往灵隐寺与飞来峰，避开午后客流。
- **下午**：到龙井村走九溪烟树，在茶园里留出休息时间。
- **晚上**：返回市区，在武林路附近自由用餐。

### 第 3 天 · 运河日常

- **上午**：参观拱宸桥与中国大运河博物馆。
- **下午**：在小河直街散步、喝咖啡，结束行程。

> 预算建议：住宿约 900 元，餐饮约 600 元，门票与市内交通约 350 元，其余留作机动。`;

type Meta = {
  chapter: number;
  model: string;
};

type ViewState = "idle" | "loading" | "done" | "error";

type FormValues = {
  destination: string;
  days: string;
  budget: string;
  preferences: string;
};

type FieldErrors = Partial<Record<keyof FormValues, string>>;

type PlanResult = {
  markdown: string;
  model: string;
  durationMs: number;
};

type AskResponse = {
  answer: string;
  model: string;
  elapsedMs: number;
};

type ApiErrorResponse = {
  errorCode?: string;
  message?: string;
};

const initialForm: FormValues = {
  destination: "杭州",
  days: "3",
  budget: "3000",
  preferences: "喜欢自然风景、本地小吃，行程不要太赶",
};

async function requestPlan(question: string): Promise<PlanResult> {
  if (USE_MOCK) {
    await new Promise((resolve) => window.setTimeout(resolve, MOCK_DELAY_MS));
    return {
      markdown: MOCK_TRIP,
      model: MOCK_MODEL,
      durationMs: MOCK_DELAY_MS,
    };
  }

  const response = await fetch("/api/plan/ask", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  });
  const payload = (await response.json()) as AskResponse | ApiErrorResponse;

  if (!response.ok) {
    const apiError = payload as ApiErrorResponse;
    throw new Error(apiError.message || `规划请求失败（${response.status}）`);
  }

  const plan = payload as AskResponse;
  return {
    markdown: plan.answer,
    model: plan.model,
    durationMs: plan.elapsedMs,
  };
}

function validate(values: FormValues): FieldErrors {
  const errors: FieldErrors = {};
  const days = Number(values.days);
  const budget = Number(values.budget);

  if (!values.destination.trim()) errors.destination = "请输入目的地";
  if (!Number.isInteger(days) || days < 1 || days > 7) errors.days = "天数需为 1 至 7 天";
  if (!Number.isFinite(budget) || budget <= 0) errors.budget = "预算需大于 0";
  return errors;
}

function buildRequest(values: FormValues) {
  const preference = values.preferences.trim() || "没有特别偏好";
  return `请为我规划一次前往${values.destination.trim()}的${values.days}天旅行，总预算为${values.budget}元，偏好是${preference}。`;
}

export function App() {
  const [meta, setMeta] = useState<Meta | null>(null);
  const [metaError, setMetaError] = useState(false);
  const [form, setForm] = useState(initialForm);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [viewState, setViewState] = useState<ViewState>("idle");
  const [waitedSeconds, setWaitedSeconds] = useState(0);
  const [result, setResult] = useState<PlanResult | null>(null);
  const [requestText, setRequestText] = useState("");
  const [planError, setPlanError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    async function loadMeta() {
      try {
        const response = await fetch("/api/meta", { signal: controller.signal });
        if (!response.ok) throw new Error(`Failed to load metadata: ${response.status}`);
        setMeta(await response.json());
      } catch (requestError) {
        if (!(requestError instanceof DOMException && requestError.name === "AbortError")) {
          setMetaError(true);
        }
      }
    }
    void loadMeta();
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (viewState !== "loading") return;
    setWaitedSeconds(0);
    const startedAt = Date.now();
    const timer = window.setInterval(() => {
      setWaitedSeconds(Math.floor((Date.now() - startedAt) / 1_000));
    }, 250);
    return () => window.clearInterval(timer);
  }, [viewState]);

  function updateField(field: keyof FormValues, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
    if (fieldErrors[field]) {
      setFieldErrors((current) => ({ ...current, [field]: undefined }));
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const errors = validate(form);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    const naturalLanguageRequest = buildRequest(form);
    setRequestText(naturalLanguageRequest);
    setPlanError("");
    setViewState("loading");

    try {
      const plan = await requestPlan(naturalLanguageRequest);
      setResult(plan);
      setViewState("done");
    } catch (error) {
      setPlanError(error instanceof Error ? error.message : "生成行程时发生未知错误");
      setViewState("error");
    }
  }

  function previewState(state: ViewState) {
    if (state === "done" && !result) {
      setResult({ markdown: MOCK_TRIP, model: MOCK_MODEL, durationMs: MOCK_DELAY_MS });
    }
    if (state === "error") setPlanError("模拟规划服务异常，请稍后重试");
    setViewState(state);
  }

  return (
    <main className="min-h-screen bg-zinc-100 text-zinc-950">
      <header className="border-b border-zinc-200 bg-white">
        <div className="mx-auto flex h-16 max-w-[1600px] items-center justify-between px-5 sm:px-8">
          <div className="flex items-center gap-3">
            <div className="grid size-9 place-items-center bg-emerald-600 text-white">
              <Route size={19} strokeWidth={2.2} aria-hidden="true" />
            </div>
            <div>
              <h1 className="text-base font-semibold leading-tight">LoopTrip</h1>
              <p className="text-xs text-zinc-500">旅行规划工作台</p>
            </div>
          </div>
          <div className="border border-zinc-200 bg-zinc-50 px-3 py-1.5 text-xs font-medium text-zinc-600" aria-live="polite">
            {meta ? `Ch${meta.chapter} · ${meta.model}` : metaError ? "Metadata unavailable" : "Loading..."}
          </div>
        </div>
      </header>

      <div className="mx-auto grid min-h-[calc(100vh-4rem)] min-w-[960px] max-w-[1600px] grid-cols-[280px_minmax(400px,1fr)_250px] xl:grid-cols-[320px_minmax(0,1fr)_280px]">
        <aside className="border-r border-zinc-200 bg-white p-5 lg:p-6">
          <div className="mb-6">
            <p className="text-xs font-semibold uppercase text-emerald-700">旅行请求</p>
            <h2 className="mt-1 text-xl font-semibold">从哪里出发？</h2>
          </div>

          <form className="space-y-5" onSubmit={handleSubmit} noValidate>
            <Field label="目的地" error={fieldErrors.destination}>
              <input
                className={inputClass(Boolean(fieldErrors.destination))}
                value={form.destination}
                onChange={(event) => updateField("destination", event.target.value)}
                placeholder="例如：杭州"
                aria-invalid={Boolean(fieldErrors.destination)}
              />
            </Field>

            <div className="grid grid-cols-2 gap-3">
              <Field label="天数" error={fieldErrors.days}>
                <div className="relative">
                  <input
                    className={`${inputClass(Boolean(fieldErrors.days))} pr-9`}
                    type="number"
                    min="1"
                    max="7"
                    value={form.days}
                    onChange={(event) => updateField("days", event.target.value)}
                    aria-invalid={Boolean(fieldErrors.days)}
                  />
                  <span className="pointer-events-none absolute right-3 top-2.5 text-sm text-zinc-400">天</span>
                </div>
              </Field>
              <Field label="预算" error={fieldErrors.budget}>
                <div className="relative">
                  <input
                    className={`${inputClass(Boolean(fieldErrors.budget))} pr-9`}
                    type="number"
                    min="1"
                    value={form.budget}
                    onChange={(event) => updateField("budget", event.target.value)}
                    aria-invalid={Boolean(fieldErrors.budget)}
                  />
                  <span className="pointer-events-none absolute right-3 top-2.5 text-sm text-zinc-400">元</span>
                </div>
              </Field>
            </div>

            <Field label="偏好">
              <textarea
                className={`${inputClass(false)} min-h-28 resize-y`}
                value={form.preferences}
                onChange={(event) => updateField("preferences", event.target.value)}
                placeholder="饮食、节奏、兴趣点等"
              />
            </Field>

            <button
              className="flex h-11 w-full items-center justify-center gap-2 bg-zinc-950 px-4 text-sm font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:bg-zinc-400"
              type="submit"
              disabled={viewState === "loading"}
            >
              <Sparkles size={17} aria-hidden="true" />
              {viewState === "loading" ? "正在规划" : "生成行程"}
              {viewState !== "loading" && <ArrowRight size={16} aria-hidden="true" />}
            </button>
          </form>
        </aside>

        <section className="min-w-0 p-4 sm:p-6 lg:p-8">
          <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <p className="text-xs font-semibold uppercase text-zinc-500">行程方案</p>
              <h2 className="mt-1 text-lg font-semibold">规划结果</h2>
            </div>
            <div className="grid grid-cols-4 border border-zinc-200 bg-white p-1" aria-label="状态预览">
              {(["idle", "loading", "done", "error"] as ViewState[]).map((state) => (
                <button
                  key={state}
                  type="button"
                  onClick={() => previewState(state)}
                  className={`min-w-16 px-2.5 py-1.5 text-xs font-medium capitalize transition-colors ${
                    viewState === state ? "bg-zinc-900 text-white" : "text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900"
                  }`}
                >
                  {state}
                </button>
              ))}
            </div>
          </div>

          <div className="min-h-[520px] border border-zinc-200 bg-white">
            {viewState === "idle" && <IdleView />}
            {viewState === "loading" && <LoadingView seconds={waitedSeconds} request={requestText} />}
            {viewState === "done" && result && <DoneView result={result} request={requestText} />}
            {viewState === "error" && <ErrorView message={planError} onRetry={() => setViewState("idle")} />}
          </div>
        </section>

        <aside className="border-l border-zinc-200 bg-white p-5 lg:p-6">
          <div className="flex items-center gap-2 text-zinc-900">
            <Clock3 size={18} aria-hidden="true" />
            <h2 className="text-sm font-semibold">执行过程</h2>
          </div>
          <div className="mt-5 border-l-2 border-dashed border-zinc-200 py-1 pl-4 text-sm leading-6 text-zinc-500">
            执行过程将在第 4 章启用
          </div>
        </aside>
      </div>
    </main>
  );
}

function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-2 block text-sm font-medium text-zinc-700">{label}</span>
      {children}
      {error && <span className="mt-1.5 block text-xs text-red-600">{error}</span>}
    </label>
  );
}

function inputClass(hasError: boolean) {
  return `w-full border bg-white px-3 py-2.5 text-sm outline-none transition-shadow placeholder:text-zinc-400 focus:ring-2 ${
    hasError ? "border-red-400 focus:ring-red-100" : "border-zinc-300 focus:border-emerald-600 focus:ring-emerald-100"
  }`;
}

function IdleView() {
  return (
    <div className="grid min-h-[520px] place-items-center px-6 text-center">
      <div className="max-w-sm">
        <div className="mx-auto grid size-14 place-items-center border border-zinc-200 bg-zinc-50 text-emerald-700">
          <Compass size={26} aria-hidden="true" />
        </div>
        <h3 className="mt-5 text-lg font-semibold">准备规划下一段旅程</h3>
        <p className="mt-2 text-sm leading-6 text-zinc-500">填写左侧旅行请求，行程方案会出现在这里。</p>
      </div>
    </div>
  );
}

function LoadingView({ seconds, request }: { seconds: number; request: string }) {
  return (
    <div className="p-6 sm:p-8">
      <div className="flex items-center justify-between border-b border-zinc-100 pb-5">
        <div className="flex items-center gap-3">
          <div className="size-3 animate-pulse bg-emerald-500" />
          <span className="text-sm font-semibold">正在编排行程</span>
        </div>
        <span className="font-mono text-xs text-zinc-500">已等待 {seconds} 秒</span>
      </div>
      {request && <p className="mt-5 line-clamp-2 text-xs leading-5 text-zinc-400">{request}</p>}
      <div className="mt-8 animate-pulse space-y-8" aria-label="行程加载中">
        {[0, 1, 2].map((item) => (
          <div key={item} className="grid grid-cols-[28px_1fr] gap-4">
            <div className="size-7 bg-zinc-200" />
            <div className="space-y-3">
              <div className="h-4 w-40 bg-zinc-200" />
              <div className="h-3 w-full bg-zinc-100" />
              <div className="h-3 w-5/6 bg-zinc-100" />
              <div className="h-3 w-2/3 bg-zinc-100" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function DoneView({ result, request }: { result: PlanResult; request: string }) {
  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-100 px-6 py-4 sm:px-8">
        <div className="flex items-center gap-2 text-sm font-medium text-emerald-700">
          <CheckCircle2 size={17} aria-hidden="true" />
          规划完成
        </div>
        <div className="flex items-center gap-2 text-xs text-zinc-600">
          <span className="border border-zinc-200 bg-zinc-50 px-2 py-1">{result.model}</span>
          <span className="border border-emerald-200 bg-emerald-50 px-2 py-1 text-emerald-800">
            {(result.durationMs / 1_000).toFixed(1)}s
          </span>
        </div>
      </div>
      <article className="px-6 py-7 sm:px-8">
        <ReactMarkdown
          components={{
            h2: ({ children }) => <h2 className="mb-6 text-2xl font-semibold">{children}</h2>,
            h3: ({ children }) => <h3 className="mb-3 mt-7 flex items-center gap-2 text-base font-semibold"><MapPinned size={17} className="text-emerald-700" aria-hidden="true" />{children}</h3>,
            ul: ({ children }) => <ul className="space-y-2 pl-5 text-sm leading-6 text-zinc-700">{children}</ul>,
            li: ({ children }) => <li className="list-disc marker:text-emerald-600">{children}</li>,
            strong: ({ children }) => <strong className="font-semibold text-zinc-900">{children}</strong>,
            blockquote: ({ children }) => <blockquote className="mt-8 border-l-2 border-emerald-500 bg-emerald-50 px-4 py-3 text-sm leading-6 text-emerald-950">{children}</blockquote>,
            p: ({ children }) => <p className="text-sm leading-6 text-zinc-700">{children}</p>,
          }}
        >
          {result.markdown}
        </ReactMarkdown>
        {request && <p className="mt-8 border-t border-zinc-100 pt-4 text-xs leading-5 text-zinc-400">请求：{request}</p>}
      </article>
    </div>
  );
}

function ErrorView({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="grid min-h-[520px] place-items-center px-6 text-center">
      <div className="max-w-sm">
        <div className="mx-auto grid size-14 place-items-center border border-red-200 bg-red-50 text-red-600">
          <AlertCircle size={26} aria-hidden="true" />
        </div>
        <h3 className="mt-5 text-lg font-semibold">行程生成失败</h3>
        <p className="mt-2 text-sm leading-6 text-zinc-500">{message || "生成行程时发生未知错误"}</p>
        <button type="button" onClick={onRetry} className="mt-5 border border-zinc-300 bg-white px-4 py-2 text-sm font-medium hover:bg-zinc-50">
          返回重试
        </button>
      </div>
    </div>
  );
}
