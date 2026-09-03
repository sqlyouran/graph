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
import { FormEvent, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";

export const USE_MOCK = false;

const COURSE_USER_ID = "course-demo-user";

const MOCK_DELAY_MS = 1_500;
const MOCK_MODEL = "qwen3.8-flash";
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
  sessionId?: string;
  terminalState?: string;
  versions?: PlanningVersion[];
  currentVersion?: number;
};
type PlanningVersion = { version:number; terminalState:string; estimatedTokens:number;
  requestDiff:Record<string, unknown>; request:PlanRequestData; plan:TripPlan | null; problems:string[]; model:string;
  elapsedMs:number; rounds:PlanningRound[]; sessionId?:string; sourceVersion?:number };
type PlanRequestData = { origin:string; destination:string; startDate:string; days:number; budget:number;
  maxHotelPrice:number; preferences:string; mustVisit:string[]; maxRounds:number };
type LiveEvent = { round:number; type:string; message:string; details:Record<string, unknown> };

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

function roundIssueLabel(round: PlanningRound) {
  if (round.problems.length === 0) return "验收通过";
  const failed = round.constraintResults.filter((item) => !item.passed);
  if (failed.length === 0) return `${round.problems.length} 项待调整`;
  return `${failed.length} 项待调整（${failed.map(item => item.name).join("、")}）`;
}

function conciseList(items: string[], maxLength = 72) {
  if (items.length === 0) return "暂无具体说明";
  const text = items.slice(0, 2).join("；");
  return text.length <= maxLength ? text : `${text.slice(0, maxLength - 1)}…`;
}

function eventItemLabel(item: Record<string, unknown>) {
  const label = String(item.label ?? item.name ?? item.id ?? "候选项");
  const facts = [item.area, item.pricePerNight != null ? `${item.pricePerNight} 元/晚` : null,
    item.ticketPrice != null ? `${item.ticketPrice} 元` : null, item.price != null ? `${item.price} 元` : null,
    item.rating != null ? `评分 ${item.rating}` : null].filter(Boolean).join(" · ");
  return facts ? `${label}（${facts}）` : label;
}

function EventDetails({ event }: { event: PlanningEvent | LiveEvent }) {
  const details = event.details;
  const candidates = Array.isArray(details.candidates) ? details.candidates as Record<string, unknown>[] : [];
  const failedChecks = event.type === "REVIEW_COMPLETED" && Array.isArray(details.constraintResults)
    ? (details.constraintResults as ConstraintCheckResult[]).filter((item) => !item.passed)
    : [];
  const selectedGroups = [
    ["去程航班", details.selectedOutboundFlight], ["返程航班", details.selectedReturnFlight],
    ["酒店", details.selectedHotels], ["景点", details.selectedAttractions],
  ] as Array<[string, unknown]>;
  return <>
    {typeof details.summary === "string" && <p className="mt-1 text-[11px] leading-4 text-zinc-500">{details.summary}</p>}
    {event.type === "TOOL_CALLED" && candidates.length > 0 && (
      <div className="mt-2 border-l border-zinc-200 pl-3 text-[11px] leading-5 text-zinc-500">
        <p className="font-medium text-zinc-600">候选 {candidates.length} 项</p>
        <p>{candidates.map(eventItemLabel).join("；")}</p>
      </div>
    )}
    {event.type === "SELECTION_COMPLETED" && selectedGroups.some(([, value]) => value != null) && (
      <div className="mt-2 border-l-2 border-emerald-200 pl-3 text-[11px] leading-5 text-zinc-600">
        <p className="font-medium text-emerald-700">模型决策结果</p>
        {selectedGroups.map(([label, value]) => {
          const items = Array.isArray(value) ? value as Record<string, unknown>[] : value ? [value as Record<string, unknown>] : [];
          return items.length > 0 ? <p key={label}><span className="text-zinc-400">{label}：</span>{items.map(eventItemLabel).join("；")}</p> : null;
        })}
      </div>
    )}
    {failedChecks.length > 0 && (
      <div className="mt-2 space-y-2 border-l-2 border-orange-300 pl-3 text-[11px] leading-4">
        {failedChecks.map((check) => (
          <div key={check.code}>
            <p className="font-semibold text-orange-800">{check.name}需要调整</p>
            <p className="mt-1 text-zinc-600"><span className="font-medium text-zinc-700">原因：</span>{conciseList(check.evidence)}</p>
            <p className="mt-1 text-orange-700"><span className="font-medium">建议：</span>{conciseList(check.suggestions)}</p>
          </div>
        ))}
      </div>
    )}
    {event.type === "PREFERENCE_LEARNED" && details.candidateId != null
      && String(details.decision ?? "").startsWith("CONFIRM") && <PreferenceCard details={details} />}
    {event.type === "PREFERENCE_CONFIRMED" && (
      <div className="mt-2 border-l-2 border-emerald-200 pl-3 text-[11px] leading-5 text-zinc-600">
        <p>{details.accepted ? "已写入用户画像" : "已放弃该候选"}：{String(details.content ?? "")}</p>
      </div>
    )}
    {event.type === "PREFERENCE_FORGOTTEN" && (
      <div className="mt-2 border-l-2 border-zinc-300 pl-3 text-[11px] leading-5 text-zinc-500">
        <p>遗忘原因：{String(details.forgetReason ?? "")}</p>
      </div>
    )}
    {event.type === "CONTEXT_ASSEMBLED" && (
      <div className="mt-2 border-l-2 border-sky-200 pl-3 text-[11px] leading-5 text-zinc-600">
        <p><span className="text-zinc-400">装配段落：</span>{Array.isArray(details.includedSections) ? (details.includedSections as string[]).join("、") : "-"}</p>
        <p><span className="text-zinc-400">丢弃段落：</span>{Array.isArray(details.droppedSections) && details.droppedSections.length > 0 ? (details.droppedSections as string[]).join("、") : "无"}</p>
        <p><span className="text-zinc-400">估算 token：</span>{String(details.estimatedTokens ?? "-")} / 可用预算 {String(details.usableBudget ?? "-")}</p>
      </div>
    )}
  </>;
}

function PreferenceCard({ details }: { details: Record<string, unknown> }) {
  const [state, setState] = useState<"pending" | "saving" | "saved" | "dismissed" | "error">("pending");
  const candidateId = String(details.candidateId);
  const condition = details.condition ? String(details.condition) : "";
  async function decide(accepted: boolean) {
    setState("saving");
    try {
      const response = await fetch(
        `/api/profiles/${COURSE_USER_ID}/confirm?candidateId=${encodeURIComponent(candidateId)}&accepted=${accepted}`,
        { method: "POST" });
      if (!response.ok) throw new Error(String(response.status));
      setState(accepted ? "saved" : "dismissed");
    } catch {
      setState("error");
    }
  }
  return (
    <div className="mt-2 rounded-md border border-violet-200 bg-violet-50 p-3 text-[11px] leading-5">
      <p className="font-semibold text-violet-800">发现偏好候选（{String(details.sessionCount ?? "?")} 个会话重复出现）</p>
      <p className="mt-1 text-zinc-700">{condition ? `当${condition}时，` : ""}{String(details.content ?? "")}</p>
      {state === "pending" && (
        <div className="mt-2 flex gap-2">
          <button className="rounded bg-violet-600 px-3 py-1 text-white hover:bg-violet-700"
            onClick={() => decide(true)}>记住</button>
          <button className="rounded border border-zinc-300 bg-white px-3 py-1 text-zinc-600 hover:bg-zinc-50"
            onClick={() => decide(false)}>不保存</button>
        </div>
      )}
      {state === "saving" && <p className="mt-2 text-zinc-500">正在提交…</p>}
      {state === "saved" && <p className="mt-2 text-emerald-700">已记住，下次规划会带上这条偏好。</p>}
      {state === "dismissed" && <p className="mt-2 text-zinc-500">已放弃，这条候选不会写入画像。</p>}
      {state === "error" && (
        <p className="mt-2 text-orange-700">提交失败，候选可能已被处理。<button className="underline" onClick={() => setState("pending")}>重试</button></p>
      )}
    </div>
  );
}

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

async function waitForSession(sessionId: string, onEvent: (event: LiveEvent) => void,
  eventOffset = 0): Promise<PlanResult> {
  return await new Promise<PlanResult>((resolve, reject) => {
    const source = new EventSource(`/api/plan/${sessionId}/events`);
    let replayedEvents = 0;
    source.onmessage = (message) => { const event = JSON.parse(message.data) as LiveEvent;
      if (event.type === "HEARTBEAT") return;
      if (replayedEvents++ < eventOffset) return;
      onEvent(event);
    };
    source.onerror = async () => { try {
      const snapshot = await fetch(`/api/plan/${sessionId}`).then(r => r.json());
      (snapshot.events as LiveEvent[]).slice(eventOffset).forEach(onEvent);
      if (snapshot.response?.terminalState) {
        source.close();
        const plan = snapshot.response as PlanResponse;
        resolve({markdown: plan.plan ? tripPlanToMarkdown(plan.plan) : "本轮未能生成可解析的结构化行程。",
          model:plan.model, durationMs:plan.elapsedMs, status:plan.status, stopReason:plan.stopReason,
          problems:plan.problems, rounds:plan.rounds, sessionId, terminalState:snapshot.terminalState,
          versions:snapshot.versions, currentVersion:snapshot.currentVersion});
      }
    } catch (e) { source.close(); reject(e); } };
  });
}

type ChatMessage = { role: "user" | "assistant"; text: string; echo?: string; confirm?: boolean; pendingUtterance?: string };

async function requestPlan(values: FormValues, onEvent: (event: LiveEvent) => void,
  onSession: (sessionId: string) => void): Promise<PlanResult> {
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

  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 60_000);
  let response: Response;
  try {
    response = await fetch("/api/plan/ask", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-User-Id": COURSE_USER_ID },
      signal: controller.signal,
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
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new Error("模型请求超过 60 秒未返回，已停止等待；请稍后重试");
    }
    throw error;
  } finally {
    window.clearTimeout(timeout);
  }
  const payload = (await response.json()) as PlanResponse | ApiErrorResponse | {sessionId:string};

  if (!response.ok) {
    const apiError = payload as ApiErrorResponse;
    throw new Error(apiError.message || `规划请求失败（${response.status}）`);
  }

  const sessionId = (payload as {sessionId:string}).sessionId;
  onSession(sessionId);
  return waitForSession(sessionId, onEvent);
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

function addLiveEvent(current: LiveEvent[], event: LiveEvent) {
  const key = JSON.stringify([event.round, event.type, event.message, event.details]);
  return current.some(item => JSON.stringify([item.round, item.type, item.message, item.details]) === key)
    ? current : [...current, event];
}

function mergeSessionResult(next: PlanResult, previous: PlanResult | null, newSession: boolean): PlanResult {
  if (!next.sessionId) return next;
  const previousVersions = previous?.versions ?? [];
  const existingSessionVersions = previousVersions.filter(version => version.sessionId === next.sessionId);
  const offset = newSession
    ? Math.max(0, ...previousVersions.map(version => version.version))
    : existingSessionVersions.length > 0
      ? existingSessionVersions[0].version - (existingSessionVersions[0].sourceVersion ?? 1)
      : 0;
  const history = previousVersions.filter(version => version.sessionId !== next.sessionId);
  const versions = (next.versions ?? []).map(version => ({
    ...version,
    version: offset + version.version,
    sourceVersion: version.version,
    sessionId: next.sessionId,
  }));
  return {
    ...next,
    versions: [...history, ...versions].sort((left, right) => left.version - right.version),
    currentVersion: next.currentVersion == null ? undefined : offset + next.currentVersion,
  };
}

function needsNewSession(result: PlanResult, form: FormValues) {
  const current = result.versions?.find(version => version.version === result.currentVersion)?.request;
  if (!current) return false;
  const requestedMustVisit = new Set(parseMustVisit(form.mustVisit));
  return current.destination !== form.destination.trim()
    || current.startDate !== form.startDate
    || current.days !== Number(form.days)
    || current.mustVisit.some(item => !requestedMustVisit.has(item));
}

function currentRequestValues(result: PlanResult): FormValues | null {
  const current = result.versions?.find(version => version.version === result.currentVersion)?.request;
  if (!current) return null;
  return {
    origin: current.origin,
    destination: current.destination,
    startDate: current.startDate,
    days: String(current.days),
    budget: String(current.budget),
    maxHotelPrice: String(current.maxHotelPrice),
    maxRounds: String(current.maxRounds),
    preferences: current.preferences === "没有特别偏好" ? "" : current.preferences,
    mustVisit: current.mustVisit.join("、"),
  };
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
  const [liveEvents, setLiveEvents] = useState<LiveEvent[]>([]);
  const [result, setResult] = useState<PlanResult | null>(null);
  const [requestText, setRequestText] = useState("");
  const [planError, setPlanError] = useState("");
  const [planningSeconds, setPlanningSeconds] = useState(0);
  const [activeSessionId, setActiveSessionId] = useState("");
  const [cancelPending, setCancelPending] = useState(false);
  const [actionError, setActionError] = useState("");
  const [loadingPreviewVersion, setLoadingPreviewVersion] = useState<number | null>(null);
  const [loadingMode, setLoadingMode] = useState<"new" | "revision">("new");
  const [chatUtterance, setChatUtterance] = useState("");
  const [chatBusy, setChatBusy] = useState(false);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const chatLogRef = useRef<HTMLDivElement>(null);
  const requestGeneration = useRef(0);

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
    const startedAt = Date.now();
    setPlanningSeconds(0);
    const timer = window.setInterval(() => {
      setPlanningSeconds(Math.floor((Date.now() - startedAt) / 1_000));
    }, 1_000);
    return () => window.clearInterval(timer);
  }, [viewState]);

  useEffect(() => {
    chatLogRef.current?.scrollTo({ top: chatLogRef.current.scrollHeight });
  }, [chatMessages]);

  function updateField(field: keyof FormValues, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
    if (fieldErrors[field]) {
      setFieldErrors((current) => ({ ...current, [field]: undefined }));
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await startNewPlan();
  }

  async function startNewPlan() {
    const errors = validate(form);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    const generation = ++requestGeneration.current;
    const previousResult = result;
    const naturalLanguageRequest = buildRequest(form);
    setRequestText(naturalLanguageRequest);
    setPlanError("");
    setLiveEvents([]);
    setCancelPending(false);
    setChatMessages([]);
    setActionError("");
    setLoadingPreviewVersion(null);
    setLoadingMode("new");
    setViewState("loading");

    try {
      const plan = await requestPlan(form, event => {
        if (requestGeneration.current !== generation) return;
        setLiveEvents(current => {
        const key = JSON.stringify([event.round, event.type, event.message, event.details]);
        return current.some(item => JSON.stringify([item.round, item.type, item.message, item.details]) === key)
          ? current
          : [...current, event];
        });
      }, sessionId => { if (requestGeneration.current === generation) setActiveSessionId(sessionId); });
      if (requestGeneration.current !== generation) return;
      setResult(mergeSessionResult(plan, previousResult, true));
      setViewState("done");
    } catch (error) {
      if (requestGeneration.current !== generation) return;
      setPlanError(error instanceof Error ? error.message : "生成行程时发生未知错误");
      setViewState("error");
    }
  }

  async function handleCancel() {
    if (!activeSessionId || cancelPending) return;
    setActionError("");
    const response = await fetch(`/api/plan/${activeSessionId}/cancel`, { method: "POST" });
    const payload = await response.json();
    if (!response.ok) { setActionError(payload.message || "取消请求失败"); return; }
    setCancelPending(true);
  }

  async function sendChat(confirmed = false, pendingUtterance?: string) {
    if (!result?.sessionId || chatBusy) return;
    const utterance = confirmed ? (pendingUtterance ?? "").trim() : chatUtterance.trim();
    if (!utterance) return;
    setChatBusy(true);
    setChatMessages(current => [...current, { role: "user", text: confirmed ? "确认执行" : utterance }]);
    if (!confirmed) setChatUtterance("");
    let revisionStarted = false;
    try {
      const response = await fetch(`/api/plan/${result.sessionId}/chat`, {
        method: "POST", headers: { "Content-Type": "application/json", "X-User-Id": COURSE_USER_ID },
        body: JSON.stringify({ utterance, confirmed }),
      });
      const payload = await response.json();
      setChatMessages(current => [...current, { role: "assistant", text: payload.text || "暂时无法处理这句话",
        echo: payload.echo, confirm: payload.decision === "CONFIRM_REQUIRED", pendingUtterance: utterance }]);
      if (payload.decision === "EXECUTED" && payload.result?.response) {
        // chat 回滚：重拉快照同步版本链，再回写表单与请求摘要
        const sessionId = result.sessionId;
        const snapshot = await fetch(`/api/plan/${sessionId}`).then(response => response.json());
        const plan = snapshot.response as PlanResponse;
        const rolledBack = {markdown:plan.plan ? tripPlanToMarkdown(plan.plan) : "该版本没有可展示的方案。", model:plan.model,
          durationMs:plan.elapsedMs, status:plan.status, stopReason:plan.stopReason, problems:plan.problems,
          rounds:plan.rounds, sessionId, terminalState:snapshot.terminalState,
          versions:snapshot.versions, currentVersion:snapshot.currentVersion} as PlanResult;
        const merged = mergeSessionResult(rolledBack, result, false);
        setResult(merged);
        const values = currentRequestValues(merged);
        if (values) { setForm(values); setRequestText(buildRequest(values)); }
      } else if (payload.decision === "EXECUTED" && payload.result?.eventOffset != null && result) {
        revisionStarted = true;
        const generation = ++requestGeneration.current;
        const sessionId = result.sessionId;
        setViewState("loading"); setLiveEvents([]); setCancelPending(false);
        setLoadingPreviewVersion(null); setLoadingMode("revision");
        // 生效即联动：槽位立刻回写表单与请求摘要，跑完后再用权威版本校正
        const slots = (payload.slots ?? {}) as Record<string, unknown>;
        const nextForm = { ...form };
        if (typeof slots.budget === "number") nextForm.budget = String(slots.budget);
        if (typeof slots.maxHotelPrice === "number") nextForm.maxHotelPrice = String(slots.maxHotelPrice);
        if (typeof slots.preferences === "string" && slots.preferences) nextForm.preferences = slots.preferences;
        if (Array.isArray(slots.mustVisit)) nextForm.mustVisit = slots.mustVisit.join("、");
        setForm(nextForm);
        setRequestText(buildRequest(nextForm));
        const revised = await waitForSession(sessionId, event => {
          if (requestGeneration.current === generation) setLiveEvents(current => addLiveEvent(current, event));
        }, Number(payload.result.eventOffset));
        if (requestGeneration.current !== generation) return;
        const merged = mergeSessionResult(revised, result, false);
        setResult(merged);
        const values = currentRequestValues(merged);
        if (values) { setForm(values); setRequestText(buildRequest(values)); }
        setViewState("done");
      }
    } catch (error) {
      setChatMessages(current => [...current, { role: "assistant", text: "请求失败，请稍后重试。" }]);
      if (revisionStarted) {
        setPlanError(error instanceof Error ? error.message : "续修失败");
        setViewState("error");
      }
    } finally {
      setChatBusy(false);
    }
  }

  async function handleRevision() {
    if (!result?.sessionId) return;
    if (needsNewSession(result, form)) {
      await startNewPlan();
      return;
    }
    const generation = ++requestGeneration.current;
    setActionError("");
    const response = await fetch(`/api/plan/${result.sessionId}/revisions`, {
      method: "POST", headers: { "Content-Type": "application/json", "X-User-Id": COURSE_USER_ID },
      body: JSON.stringify({budget:Number(form.budget), maxHotelPrice:Number(form.maxHotelPrice),
        preferences:form.preferences.trim(), maxRounds:Number(form.maxRounds), mustVisit:parseMustVisit(form.mustVisit),
        destination:form.destination.trim(), startDate:form.startDate, days:Number(form.days)}),
    });
    const payload = await response.json();
    if (!response.ok) { setActionError([payload.message, payload.suggestion].filter(Boolean).join("；")); return; }
    if (requestGeneration.current !== generation) return;
    setRequestText(buildRequest(form));
    setViewState("loading"); setLiveEvents([]); setCancelPending(false);
    setLoadingPreviewVersion(null);
    setLoadingMode("revision");
    const eventOffset = Number(payload.eventOffset ?? 0);
    try { const revised = await waitForSession(result.sessionId, event => {
      if (requestGeneration.current === generation) setLiveEvents(current => addLiveEvent(current, event));
    }, eventOffset);
      if (requestGeneration.current !== generation) return;
      const merged = mergeSessionResult(revised, result, false);
      setResult(merged);
      const values = currentRequestValues(merged);
      if (values) { setForm(values); setRequestText(buildRequest(values)); }
      setViewState("done");
    } catch (error) {
      if (requestGeneration.current !== generation) return;
      setPlanError(error instanceof Error ? error.message : "续修失败"); setViewState("error");
    }
  }

  async function handleRollback(version: number) {
    if (!result?.sessionId) return;
    const selected = result.versions?.find(item => item.version === version);
    const targetSessionId = selected?.sessionId ?? result.sessionId;
    const sourceVersion = selected?.sourceVersion ?? version;
    setActionError("");
    const response = await fetch(`/api/plan/${targetSessionId}/rollback?version=${sourceVersion}`, {method:"POST"});
    const payload = await response.json();
    if (!response.ok) { setActionError([payload.message, payload.suggestion].filter(Boolean).join("；")); return; }
    const snapshot = await fetch(`/api/plan/${targetSessionId}`).then(r => r.json());
    const plan = snapshot.response as PlanResponse;
    const rolledBack = {markdown:plan.plan ? tripPlanToMarkdown(plan.plan) : "该版本没有可展示的方案。", model:plan.model,
      durationMs:plan.elapsedMs, status:plan.status, stopReason:plan.stopReason, problems:plan.problems,
      rounds:plan.rounds, sessionId:targetSessionId, terminalState:snapshot.terminalState,
      versions:snapshot.versions, currentVersion:snapshot.currentVersion} as PlanResult;
    const merged = mergeSessionResult(rolledBack, result, false);
    setResult(merged);
    const values = currentRequestValues(merged);
    if (values) { setForm(values); setRequestText(buildRequest(values)); }
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
          {result?.sessionId && <div className="mt-8 border-t border-zinc-200 pt-5">
            <p className="text-xs font-semibold text-zinc-700">也可以直接说</p>
            {chatMessages.length > 0 && <div ref={chatLogRef} className="mt-2 flex max-h-80 flex-col gap-2 overflow-y-auto pr-1">
              {chatMessages.map((message, index) => message.role === "user"
                ? <div key={index} className="max-w-[85%] self-end rounded-md bg-zinc-900 px-3 py-1.5 text-xs leading-5 text-white">{message.text}</div>
                : <div key={index} className="max-w-[92%] self-start border-l-2 border-emerald-300 bg-emerald-50 px-3 py-2 text-xs leading-5 text-zinc-700">
                    {message.echo && <p className="font-medium">{message.echo}</p>}
                    <p>{message.text}</p>
                    {message.confirm && index === chatMessages.length - 1 && !chatBusy &&
                      <button type="button" onClick={() => void sendChat(true, message.pendingUtterance)} className="mt-2 bg-zinc-900 px-3 py-1.5 text-xs text-white">确认执行</button>}
                  </div>)}
            </div>}
            <div className="mt-2 flex gap-2">
              <input className={inputClass(false)} value={chatUtterance} onChange={event => setChatUtterance(event.target.value)}
                placeholder="例如：太贵了、还是上一版好" disabled={chatBusy} onKeyDown={event => { if (event.key === "Enter") { event.preventDefault(); void sendChat(); } }} />
              <button type="button" onClick={() => void sendChat()} disabled={chatBusy} className="shrink-0 bg-zinc-900 px-3 text-xs font-medium text-white disabled:opacity-40">{chatBusy ? "发送中" : "发送"}</button>
            </div>
          </div>}
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
            {viewState === "loading" && <LoadingView events={liveEvents} request={requestText} seconds={planningSeconds}
              cancelPending={cancelPending} canCancel={Boolean(activeSessionId)} onCancel={handleCancel}
              previousResult={result} previewVersion={loadingPreviewVersion} onPreviewVersion={setLoadingPreviewVersion}
              mode={loadingMode} />}
            {viewState === "done" && result && <DoneView result={result} request={requestText}
              actionError={actionError} onRevision={handleRevision} onRollback={handleRollback}
              startsNewSession={needsNewSession(result, form)} />}
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

function LoadingView({ events, request, seconds, cancelPending, canCancel, onCancel,
  previousResult, previewVersion, onPreviewVersion, mode }: {
  events: LiveEvent[]; request: string; seconds: number; cancelPending:boolean; canCancel:boolean; onCancel:()=>void;
  previousResult:PlanResult | null; previewVersion:number | null; onPreviewVersion:(version:number | null)=>void;
  mode:"new" | "revision" }) {
  const roundNumbers = [...new Set(events.filter(event => event.round > 0).map(event => event.round))].sort((a, b) => a - b);
  const [activeRound, setActiveRound] = useState<number | null>(null);
  const newestRound = roundNumbers.at(-1) ?? null;
  useEffect(() => { if (newestRound != null) setActiveRound(newestRound); }, [newestRound]);
  const selectedRound = activeRound != null && roundNumbers.includes(activeRound) ? activeRound : newestRound;
  const roundEvents = selectedRound == null ? [] : events.filter(event => event.round === selectedRound);
  const sessionEvents = events.filter(event => event.round <= 0);
  const latestEvent = events.at(-1);
  const hasCandidates = events.some((event) => event.type === "TOOL_CALLED" && Array.isArray(event.details.candidates) && event.details.candidates.length > 0);
  const hasSelection = events.some((event) => event.type === "SELECTION_COMPLETED");
  const phase = hasCandidates && !hasSelection
    ? "候选数据已汇总，等待模型比较预算、偏好与行程约束后做出选择"
    : latestEvent?.type === "TOOL_CALLED"
      ? "正在汇总工具数据"
      : latestEvent?.message || "正在启动规划";
  const preview = previousResult?.versions?.find(version => version.version === previewVersion);
  const nextVersion = Math.max(0, ...(previousResult?.versions?.map(version => version.version) ?? [])) + 1;
  const hasTerminalEvent = events.some(event => ["COMPLETED", "CANCELLED", "MAX_ROUNDS_REACHED", "GUARD_TRIGGERED"].includes(event.type));
  const isCancelled = events.some(event => event.type === "CANCELLED");
  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-100 px-6 py-4 sm:px-8">
        <div className="flex items-center gap-3">
          <div className={`size-3 ${isCancelled ? "bg-zinc-400" : "animate-pulse bg-emerald-500"}`} />
          <span className="text-sm font-semibold">{isCancelled ? "规划已取消，等待新的指令" : hasTerminalEvent ? "正在整理规划结果" : cancelPending ? "取消已接收，正在等待当前轮完成" : "正在编排行程"}</span>
        </div>
        <span className="font-mono text-xs tabular-nums text-zinc-500">{seconds} 秒</span>
      </div>
      {previousResult?.versions && previousResult.versions.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 border-b border-zinc-100 px-6 py-3 sm:px-8">
          <span className="mr-1 text-xs font-medium text-zinc-500">版本</span>
          {previousResult.versions.map(version => (
            <button key={version.version} type="button" onClick={() => onPreviewVersion(version.version)}
              className={`border px-3 py-2 text-xs ${version.version === previewVersion
                ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                : "border-zinc-200 bg-white text-zinc-700"}`}>
              v{version.version}
            </button>
          ))}
          <button type="button" onClick={() => onPreviewVersion(null)}
            className={`border px-3 py-2 text-xs ${previewVersion == null
              ? "border-zinc-900 bg-zinc-900 text-white" : "border-zinc-200 bg-white text-zinc-700"}`}>
            {mode === "new" ? "新行程" : `v${nextVersion}`} · {isCancelled ? "已取消" : "规划中"}
          </button>
        </div>
      )}
      {preview && (
        <div>
          <div className="border-b border-zinc-100 bg-zinc-50 px-6 py-3 text-xs text-zinc-600 sm:px-8">
            v{preview.version} 执行过程；{mode === "new" ? "新行程" : `v${nextVersion}`}{isCancelled ? "已取消" : "仍在后台规划"}
          </div>
          <ArchivedRoundProcess version={preview} />
        </div>
      )}
      {previewVersion == null && isCancelled && <div className="p-6 sm:p-8">
        <div className="mt-4 flex items-center gap-2 border-l-2 border-zinc-400 bg-zinc-50 px-4 py-3 text-xs text-zinc-600" aria-live="polite">
          <Clock3 size={14} className="shrink-0" aria-hidden="true" />
          <span>规划已取消，等待新的指令：可在左侧修改需求后重新生成，或点击上方版本查看已有方案。</span>
        </div>
      </div>}
      {previewVersion == null && !isCancelled && <div className="p-6 sm:p-8">
      {!hasTerminalEvent && <button type="button" onClick={onCancel} disabled={!canCancel || cancelPending}
        className="mt-4 border border-red-200 bg-white px-3 py-2 text-xs font-medium text-red-700 disabled:cursor-not-allowed disabled:text-zinc-400">
        {cancelPending ? "正在等待当前轮完成" : "取消规划"}
      </button>}
      {cancelPending && <p className="mt-2 text-xs leading-5 text-zinc-500">
        当前模型调用不会被中途截断；本轮返回后将立即停止，不会开始下一轮，并保留目前最优方案。
      </p>}
      {request && <p className="mt-5 line-clamp-2 text-xs leading-5 text-zinc-400">{request}</p>}
      <div className="mt-5 flex items-center gap-2 border-l-2 border-emerald-500 bg-emerald-50 px-4 py-3 text-xs text-emerald-800" aria-live="polite">
        <Clock3 size={14} className="shrink-0" aria-hidden="true" />
        <span>{phase}</span>
      </div>
      <div className="mt-8 space-y-3" aria-label="行程加载中">
        {roundNumbers.length > 0 && <div className="flex flex-wrap gap-2 border-b border-zinc-100 pb-3" aria-label="规划轮次">
          {roundNumbers.map(round => <button key={round} type="button" onClick={() => setActiveRound(round)}
            className={`border px-3 py-1.5 text-xs ${selectedRound === round
              ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-zinc-200 text-zinc-600"}`}>
            第 {round} 轮
          </button>)}
        </div>}
        {sessionEvents.map((event, index) => <div key={`session-${index}`} className="border-l-2 border-zinc-300 py-1 pl-4 text-xs text-zinc-600">
          <p>会话 · {event.message}</p><EventDetails event={event} />
        </div>)}
        {roundEvents.map((event, index) => <div key={`${selectedRound}-${index}`} className="border-l-2 border-emerald-300 py-1 pl-4 text-xs text-zinc-600">
          <p>第 {selectedRound} 轮 · {event.message}</p><EventDetails event={event} />
        </div>)}
        {events.length === 0 && <div className="border-l-2 border-dashed border-zinc-300 py-2 pl-4 text-sm text-zinc-500">
          等待规划开始
        </div>}
        {events.length === 0 && [0, 1, 2].map((item) => (
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
      </div>}
    </div>
  );
}

function DoneView({ result, request, actionError, onRevision, onRollback, startsNewSession }: { result: PlanResult; request: string;
  actionError:string; onRevision:()=>void; onRollback:(version:number)=>void; startsNewSession:boolean }) {
  const completed = result.status === "COMPLETED";
  const cancelled = result.terminalState === "CANCELLED";
  const outcomeLabel = completed ? "规划完成" : cancelled ? "规划已取消" : "规划暂未通过";
  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-100 px-6 py-4 sm:px-8">
        <div className={`flex items-center gap-2 text-sm font-medium ${completed ? "text-emerald-700" : "text-orange-700"}`}>
          {completed ? <CheckCircle2 size={17} aria-hidden="true" /> : <AlertCircle size={17} aria-hidden="true" />}
          {outcomeLabel}
        </div>
        <div className="flex items-center gap-2 text-xs text-zinc-600">
          <span className="border border-zinc-200 bg-zinc-50 px-2 py-1">{result.model}</span>
          <span className="border border-emerald-200 bg-emerald-50 px-2 py-1 text-emerald-800">
            {(result.durationMs / 1_000).toFixed(1)}s
          </span>
        </div>
      </div>
      <div className={`border-b px-6 py-3 text-xs sm:px-8 ${completed ? "border-emerald-100 bg-emerald-50 text-emerald-800" : "border-orange-100 bg-orange-50 text-orange-800"}`}>
        {cancelled ? "这是取消时保留的当前最优方案，并非已通过全部验收的最终方案。你可以修改左侧需求后继续规划或生成新行程。" : `${result.stopReason} · 共执行 ${result.rounds.length} 轮`}
        {!completed && !cancelled && result.problems.length > 0 && result.rounds.length > 0 &&
          ` · ${roundIssueLabel(result.rounds.at(-1)!)}`}
      </div>
      {result.sessionId && <div className="flex flex-wrap items-center gap-2 border-b border-zinc-100 px-6 py-3 sm:px-8">
        <button type="button" onClick={onRevision} className="bg-zinc-900 px-3 py-2 text-xs font-medium text-white">
          {startsNewSession ? "按左侧需求生成新行程" : "按左侧需求续修"}
        </button>
        <span className="text-xs font-medium text-zinc-500">版本</span>
        {result.versions?.map(version => <button key={version.version} type="button"
          disabled={version.version === result.currentVersion} onClick={() => onRollback(version.version)}
          className="border border-zinc-200 px-3 py-2 text-xs text-zinc-700 disabled:bg-emerald-50 disabled:text-emerald-700">
          v{version.version}{version.version === result.currentVersion ? " · 当前" : " · 回滚"}
        </button>)}
        {actionError && <p className="w-full text-xs text-red-600">{actionError}</p>}
      </div>}
      <PlanArticle markdown={result.markdown} request={request} />
    </div>
  );
}

function PlanArticle({ markdown, request }: { markdown:string; request:string }) {
  return <article className="px-6 py-7 sm:px-8">
    <ReactMarkdown components={{
      h2: ({ children }) => <h2 className="mb-6 text-2xl font-semibold">{children}</h2>,
      h3: ({ children }) => <h3 className="mb-3 mt-7 flex items-center gap-2 text-base font-semibold"><MapPinned size={17} className="text-emerald-700" aria-hidden="true" />{children}</h3>,
      ul: ({ children }) => <ul className="space-y-2 pl-5 text-sm leading-6 text-zinc-700">{children}</ul>,
      li: ({ children }) => <li className="list-disc marker:text-emerald-600">{children}</li>,
      strong: ({ children }) => <strong className="font-semibold text-zinc-900">{children}</strong>,
      blockquote: ({ children }) => <blockquote className="mt-8 border-l-2 border-emerald-500 bg-emerald-50 px-4 py-3 text-sm leading-6 text-emerald-950">{children}</blockquote>,
      p: ({ children }) => <p className="text-sm leading-6 text-zinc-700">{children}</p>,
    }}>{markdown}</ReactMarkdown>
    {request && <p className="mt-8 border-t border-zinc-100 pt-4 text-xs leading-5 text-zinc-400">请求：{request}</p>}
  </article>;
}

function ArchivedRoundProcess({ version }: { version:PlanningVersion }) {
  const [activeRound, setActiveRound] = useState(version.rounds.at(-1)?.round ?? 1);
  useEffect(() => { setActiveRound(version.rounds.at(-1)?.round ?? 1); }, [version.version, version.rounds]);
  const round = version.rounds.find(item => item.round === activeRound) ?? version.rounds.at(-1);
  if (!round) {
    return <div className="px-6 py-8 text-sm text-zinc-500 sm:px-8">该版本创建于过程归档升级前，暂无完整轮次事件。</div>;
  }
  return <div className="px-6 py-6 sm:px-8">
    {version.rounds.length > 1 && <div className="mb-5 flex flex-wrap gap-2 border-b border-zinc-100 pb-4" aria-label={`v${version.version} 轮次`}>
      {version.rounds.map(item => <button key={item.round} type="button" onClick={() => setActiveRound(item.round)}
        className={`border px-3 py-1.5 text-xs ${item.round === round.round
          ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-zinc-200 text-zinc-600"}`}>
        第 {item.round} 轮
      </button>)}
    </div>}
    <div className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-100 pb-4">
      <div>
        <p className="text-sm font-semibold text-zinc-900">第 {round.round} 轮</p>
        <p className="mt-1 text-xs text-zinc-500">{version.model} · 本版本共 {version.rounds.length} 轮 · 约 {version.estimatedTokens} tokens</p>
      </div>
      <span className={`text-xs font-semibold ${round.problems.length === 0 ? "text-emerald-700" : "text-orange-700"}`}>
        {roundIssueLabel(round)}
      </span>
    </div>
    {round.feedbackReceived.length > 0 && <div className="mt-4 border-l-2 border-zinc-200 pl-3 text-xs leading-5 text-zinc-600">
      已根据上一轮反馈调整方案
    </div>}
    <ol className="mt-5 space-y-5">
      {round.events.map(event => <li key={event.sequence} className="grid grid-cols-[14px_1fr] gap-3">
        <div className={`mt-1.5 size-2 ${event.type === "COMPLETED" ? "bg-emerald-500" : "bg-zinc-300"}`} />
        <div className="min-w-0">
          <p className="text-xs font-medium leading-5 text-zinc-800">{event.message}</p>
          <EventDetails event={event} />
        </div>
      </li>)}
    </ol>
  </div>;
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
            {roundIssueLabel(round)}
          </span>
        </div>
        {round.feedbackReceived.length > 0 && <p className="mt-2 text-[11px] leading-4 text-zinc-500">已根据上一轮反馈调整方案</p>}
        {round.problems.length > 0 && round.constraintResults.length === 0 && (
          <div className="mt-2 border-l-2 border-orange-300 pl-3">
            <p className="text-[11px] leading-4 text-orange-700">本轮有 {round.problems.length} 个待修问题，详见下方验收项。</p>
          </div>
        )}
        <div className="mt-3 divide-y divide-zinc-100">
        {round.constraintResults.filter(check => !check.passed).map((check) => (
          <div key={round.round + "-" + check.code} className="py-3">
            <p className="text-xs font-semibold text-zinc-800">{check.name}需要调整</p>
            <p className="mt-1 text-[11px] leading-4 text-zinc-600"><span className="font-medium">原因：</span>{conciseList(check.evidence)}</p>
            <p className="mt-1 text-[11px] leading-4 text-orange-700"><span className="font-medium">建议：</span>{conciseList(check.suggestions)}</p>
          </div>
        ))}
        {round.constraintResults.length > 0 && round.constraintResults.every(check => check.passed) &&
          <p className="py-3 text-xs text-emerald-700">行程验收已通过</p>}
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
                {event.type === "SELECTION_COMPLETED" && <span className="text-[10px] text-emerald-700">已选择</span>}
              </div>
              <p className="mt-0.5 text-xs leading-5 text-zinc-700">{event.message}</p>
              <EventDetails event={event} />
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
