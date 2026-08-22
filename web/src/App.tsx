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
  origin: string;
  destination: string;
  startDate: string;
  days: string;
  budget: string;
  maxHotelPrice: string;
  maxRounds: string;
  preferences: string;
  mustVisit: string;
};

type FieldErrors = Partial<Record<keyof FormValues, string>>;

type PlanResult = {
  markdown: string;
  model: string;
  durationMs: number;
  status: "COMPLETED" | "MAX_ROUNDS";
  stopReason: string;
  problems: string[];
  rounds: PlanningRound[];
};

type PlanningEvent = {
  sequence: number;
  round: number;
  type: string;
  message: string;
  details: Record<string, unknown>;
};

type PlanningRound = {
  round: number;
  plan: TripPlan | null;
  problems: string[];
  constraintResults: ConstraintCheckResult[];
  feedbackReceived: string[];
  events: PlanningEvent[];
};

type ConstraintCheckResult = {
  code: string;
  name: string;
  severity: "HARD" | "SOFT";
  passed: boolean;
  evidence: string[];
  suggestions: string[];
};

type PlanResponse = {
  plan: TripPlan | null;
  model: string;
  elapsedMs: number;
  status: "COMPLETED" | "MAX_ROUNDS";
  stopReason: string;
  problems: string[];
  rounds: PlanningRound[];
};

type TripFlight = {
  flightNumber: string;
  origin: string;
  destination: string;
  departureTime: string;
  arrivalTime: string;
  price: number | null;
};

type TripActivity = {
  name: string;
  type: string;
  startTime: string;
  endTime: string;
  area: string;
  price: number | null;
};

type TripPlan = {
  origin: string;
  destination: string;
  startDate: string;
  days: number;
  outboundFlight: TripFlight | null;
  returnFlight: TripFlight | null;
  dailyPlans: Array<{
    date: string;
    hotel: { name: string; area: string; pricePerNight: number | null } | null;
    activities: TripActivity[];
  }>;
};

type ApiErrorResponse = {
  errorCode?: string;
  message?: string;
};

const initialForm: FormValues = {
  origin: "上海",
  destination: "杭州",
  startDate: "2026-10-01",
  days: "3",
  budget: "3000",
  maxHotelPrice: "700",
  maxRounds: "2",
  preferences: "喜欢自然风景、本地小吃，行程不要太赶",
  mustVisit: "西湖、灵隐寺",
};

async function requestPlan(values: FormValues): Promise<PlanResult> {
  if (USE_MOCK) {
    await new Promise((resolve) => window.setTimeout(resolve, MOCK_DELAY_MS));
    return {
      markdown: MOCK_TRIP,
      model: MOCK_MODEL,
      durationMs: MOCK_DELAY_MS,
      status: "COMPLETED",
      stopReason: "基础契约通过",
      problems: [],
      rounds: [],
    };
  }

  const response = await fetch("/api/plan/ask", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      origin: values.origin.trim(),
      destination: values.destination.trim(),
      startDate: values.startDate,
      days: Number(values.days),
      budget: Number(values.budget),
      maxHotelPrice: Number(values.maxHotelPrice),
      preferences: values.preferences.trim(),
      mustVisit: parseMustVisit(values.mustVisit),
      maxRounds: Number(values.maxRounds),
    }),
  });
  const payload = (await response.json()) as PlanResponse | ApiErrorResponse;

  if (!response.ok) {
    const apiError = payload as ApiErrorResponse;
    throw new Error(apiError.message || `规划请求失败（${response.status}）`);
  }

  const plan = payload as PlanResponse;
  return {
    markdown: plan.plan ? tripPlanToMarkdown(plan.plan) : "本轮未能生成可解析的结构化行程。",
    model: plan.model,
    durationMs: plan.elapsedMs,
    status: plan.status,
    stopReason: plan.stopReason,
    problems: plan.problems,
    rounds: plan.rounds,
  };
}

function tripPlanToMarkdown(plan: TripPlan) {
  const flightLine = (label: string, flight: TripFlight | null) => flight
    ? `- **${label}**：${flight.flightNumber}，${formatDateTime(flight.departureTime)} 从${flight.origin}出发，${formatDateTime(flight.arrivalTime)}抵达${flight.destination}${flight.price == null ? "" : `，${flight.price} 元`}`
    : `- **${label}**：暂无数据`;
  const days = plan.dailyPlans.map((day, index) => {
    const hotel = day.hotel
      ? `- **酒店**：${day.hotel.name}（${day.hotel.area}${day.hotel.pricePerNight == null ? "" : `，${day.hotel.pricePerNight} 元/晚`}）`
      : "- **酒店**：暂无数据";
    const activities = day.activities.length > 0
      ? day.activities.map((activity) => `- **${activity.startTime}-${activity.endTime} · ${activity.type}**：${activity.name}（${activity.area}${activity.price == null ? "" : `，${activity.price} 元`}）`).join("\n")
      : "- **活动**：暂无数据";
    return `### 第 ${index + 1} 天 · ${day.date}\n\n${hotel}\n${activities}`;
  }).join("\n\n");
  return `## ${plan.origin}至${plan.destination} ${plan.days} 日行程\n\n### 往返航班\n\n${flightLine("去程", plan.outboundFlight)}\n${flightLine("返程", plan.returnFlight)}\n\n${days}`;
}

function formatDateTime(value: string) {
  return value ? value.replace("T", " ") : "时间暂无数据";
}

function validate(values: FormValues): FieldErrors {
  const errors: FieldErrors = {};
  const days = Number(values.days);
  const budget = Number(values.budget);
  const maxHotelPrice = Number(values.maxHotelPrice);
  const maxRounds = Number(values.maxRounds);

  if (!values.origin.trim()) errors.origin = "请输入出发地";
  if (!values.destination.trim()) errors.destination = "请输入目的地";
  if (!isIsoDate(values.startDate)) errors.startDate = "请选择有效的出发日期";
  if (!Number.isInteger(days) || days < 1 || days > 7) errors.days = "天数需为 1 至 7 天";
  if (!Number.isFinite(budget) || budget <= 0) errors.budget = "预算需大于 0";
  if (!Number.isFinite(maxHotelPrice) || maxHotelPrice <= 0) errors.maxHotelPrice = "每晚限价需大于 0";
  if (!Number.isInteger(maxRounds) || maxRounds < 1 || maxRounds > 5) errors.maxRounds = "轮次需为 1 至 5";
  return errors;
}

function buildRequest(values: FormValues) {
  const preference = values.preferences.trim() || "没有特别偏好";
  const mustVisit = parseMustVisit(values.mustVisit);
  return `请为我规划一次${values.startDate}从${values.origin.trim()}飞往${values.destination.trim()}的${values.days}日旅行。总预算为${values.budget}元，酒店每晚不超过${values.maxHotelPrice}元，偏好是${preference}。必去景点：${mustVisit.length ? mustVisit.join("、") : "无"}。请查询航班、酒店、景点和每天的天气，并按天输出。`;
}

function parseMustVisit(value: string) {
  return value.split(/[,，、\n]/).map((item) => item.trim()).filter(Boolean);
}

function isIsoDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
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
    setResult(null);
    setViewState("loading");

    try {
      const plan = await requestPlan(form);
      setResult(plan);
      setViewState("done");
    } catch (error) {
      setPlanError(error instanceof Error ? error.message : "生成行程时发生未知错误");
      setViewState("error");
    }
  }

  function previewState(state: ViewState) {
    if (state === "done" && !result) {
      setResult({
        markdown: MOCK_TRIP,
        model: MOCK_MODEL,
        durationMs: MOCK_DELAY_MS,
        status: "COMPLETED",
        stopReason: "基础契约通过",
        problems: [],
        rounds: [],
      });
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

          <form className="space-y-4" onSubmit={handleSubmit} noValidate>
            <Field label="出发地" error={fieldErrors.origin}>
              <input
                className={inputClass(Boolean(fieldErrors.origin))}
                value={form.origin}
                onChange={(event) => updateField("origin", event.target.value)}
                placeholder="例如：上海"
                aria-invalid={Boolean(fieldErrors.origin)}
              />
            </Field>

            <Field label="目的地" error={fieldErrors.destination}>
              <input
                className={inputClass(Boolean(fieldErrors.destination))}
                value={form.destination}
                onChange={(event) => updateField("destination", event.target.value)}
                placeholder="例如：杭州"
                aria-invalid={Boolean(fieldErrors.destination)}
              />
            </Field>

            <Field label="出发日期" error={fieldErrors.startDate}>
              <input
                className={inputClass(Boolean(fieldErrors.startDate))}
                type="date"
                value={form.startDate}
                onChange={(event) => updateField("startDate", event.target.value)}
                aria-invalid={Boolean(fieldErrors.startDate)}
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

            <Field label="酒店每晚限价" error={fieldErrors.maxHotelPrice}>
              <div className="relative">
                <input
                  className={`${inputClass(Boolean(fieldErrors.maxHotelPrice))} pr-9`}
                  type="number"
                  min="1"
                  value={form.maxHotelPrice}
                  onChange={(event) => updateField("maxHotelPrice", event.target.value)}
                  aria-invalid={Boolean(fieldErrors.maxHotelPrice)}
                />
                <span className="pointer-events-none absolute right-3 top-2.5 text-sm text-zinc-400">元</span>
              </div>
            </Field>

            <Field label="最大修订轮次" error={fieldErrors.maxRounds}>
              <div className="relative">
                <input
                  className={`${inputClass(Boolean(fieldErrors.maxRounds))} pr-9`}
                  type="number"
                  min="1"
                  max="5"
                  value={form.maxRounds}
                  onChange={(event) => updateField("maxRounds", event.target.value)}
                  aria-invalid={Boolean(fieldErrors.maxRounds)}
                />
                <span className="pointer-events-none absolute right-3 top-2.5 text-sm text-zinc-400">轮</span>
              </div>
            </Field>

            <Field label="偏好">
              <textarea
                className={`${inputClass(false)} min-h-28 resize-y`}
                value={form.preferences}
                onChange={(event) => updateField("preferences", event.target.value)}
                placeholder="饮食、节奏、兴趣点等"
              />
            </Field>

            <Field label="必去景点">
              <input
                className={inputClass(false)}
                value={form.mustVisit}
                onChange={(event) => updateField("mustVisit", event.target.value)}
                placeholder="例如：西湖、灵隐寺"
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
          <ProcessView state={viewState} result={result} />
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
  const completed = result.status === "COMPLETED";
  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-100 px-6 py-4 sm:px-8">
        <div className={`flex items-center gap-2 text-sm font-medium ${completed ? "text-emerald-700" : "text-orange-700"}`}>
          {completed ? <CheckCircle2 size={17} aria-hidden="true" /> : <AlertCircle size={17} aria-hidden="true" />}
          {completed ? "规划完成" : "已达最大轮次"}
        </div>
        <div className="flex items-center gap-2 text-xs text-zinc-600">
          <span className="border border-zinc-200 bg-zinc-50 px-2 py-1">{result.model}</span>
          <span className="border border-emerald-200 bg-emerald-50 px-2 py-1 text-emerald-800">
            {(result.durationMs / 1_000).toFixed(1)}s
          </span>
        </div>
      </div>
      <div className={`border-b px-6 py-3 text-xs sm:px-8 ${completed ? "border-emerald-100 bg-emerald-50 text-emerald-800" : "border-orange-100 bg-orange-50 text-orange-800"}`}>
        {result.stopReason} · 共执行 {result.rounds.length} 轮
        {!completed && result.problems.length > 0 && ` · 未解决：${result.problems.join("；")}`}
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

function ProcessView({ state, result }: { state: ViewState; result: PlanResult | null }) {
  if (state === "loading") {
    return (
      <div className="mt-5 border-l-2 border-emerald-300 py-1 pl-4 text-sm text-zinc-600">
        Loop 正在执行，完成后返回完整事件。
      </div>
    );
  }

  if (!result || result.rounds.length === 0) {
    return (
      <div className="mt-5 border-l-2 border-dashed border-zinc-200 py-1 pl-4 text-sm leading-6 text-zinc-500">
        提交规划后显示轮次、工具、检查与反馈。
      </div>
    );
  }

  return (
    <div className="mt-5 space-y-6">
    {result.rounds.map((round) => (
      <section key={round.round} className="border-t border-zinc-200 pt-4">
        <div className="flex items-center justify-between">
          <span className="text-xs font-semibold text-zinc-800">第 {round.round} 轮</span>
          <span className={round.problems.length === 0 ? "text-[10px] font-semibold text-emerald-700" : "text-[10px] font-semibold text-orange-700"}>
            {round.problems.length === 0 ? "验收通过" : round.problems.length + " 个问题"}
          </span>
        </div>
        {round.feedbackReceived.length > 0 && <p className="mt-2 text-[11px] leading-4 text-zinc-500">收到反馈：{round.feedbackReceived.join("；")}</p>}
        {round.problems.length > 0 && (
          <div className="mt-2 border-l-2 border-orange-300 pl-3">
            {round.problems.map((problem) => <p key={problem} className="text-[11px] leading-4 text-orange-700">{problem}</p>)}
          </div>
        )}
        <div className="mt-3 divide-y divide-zinc-100">
        {round.constraintResults.map((check) => (
          <div key={round.round + "-" + check.code} className="py-2">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-semibold text-zinc-800">{check.code} · {check.name}</span>
              <span className={`text-[10px] font-semibold ${check.passed ? "text-emerald-700" : "text-red-700"}`}>
                {check.severity} · {check.passed ? "通过" : "未通过"}
              </span>
            </div>
            {check.evidence.map((item) => <p key={item} className="mt-1 text-[11px] leading-4 text-zinc-500">{item}</p>)}
            {!check.passed && check.suggestions.map((item) => <p key={item} className="mt-1 text-[11px] leading-4 text-orange-700">建议：{item}</p>)}
          </div>
        ))}
        </div>
    <ol className="mt-5 space-y-4">
      {round.events.map((event) => {
        const isFinal = event.type === "COMPLETED" || event.type === "MAX_ROUNDS_REACHED";
        return (
          <li key={event.sequence} className="grid grid-cols-[18px_1fr] gap-3">
            <div className={`mt-1 size-2.5 ${isFinal ? (result.status === "COMPLETED" ? "bg-emerald-500" : "bg-orange-500") : "bg-zinc-300"}`} />
            <div className="min-w-0">
              <div className="flex items-center justify-between gap-2">
                <span className="text-[11px] font-semibold uppercase text-zinc-400">第 {event.round} 轮</span>
                {event.type === "TOOL_CALLED" && <span className="text-[10px] text-emerald-700">工具</span>}
              </div>
              <p className="mt-0.5 text-xs leading-5 text-zinc-700">{event.message}</p>
              {event.type === "REVIEW_COMPLETED" && Array.isArray(event.details.problems) && event.details.problems.length > 0 && (
                <p className="mt-1 text-[11px] leading-4 text-orange-700">{event.details.problems.join("；")}</p>
              )}
            </div>
          </li>
        );
      })}
    </ol>
      </section>
    ))}
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
