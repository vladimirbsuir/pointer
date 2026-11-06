import { useEffect, useReducer } from "react";
import { api, unwrap } from "@/shared/api/client";
import { core } from "@/shared/api/core";
import {
  toAppError,
  type AppError,
  type Item,
  type Session,
  type SessionEvent,
} from "@/shared/api/types";
import { onRealtimeMessage } from "@/shared/realtime/realtime";

type TurnError = { code: string; message: string };

export type Usage = {
  contextTokens: number;
  contextWindow: number | null;
  totalInputTokens: number;
  totalOutputTokens: number;
  estimatedCost: number | null;
};

export type ConversationState = {
  session: Session | null;
  items: Item[];
  lastSeq: number;
  loading: boolean;
  loadError: AppError | null;
  turnError: TurnError | null;
  usage: Usage | null;
  changesVersion: number;
};

type Action =
  | { type: "reset" }
  | { type: "loaded"; session: Session; items: Item[] }
  | { type: "failed"; error: AppError }
  | { type: "event"; event: SessionEvent }
  | { type: "delta"; itemId: string; delta: string }
  | { type: "output"; itemId: string; delta: string }
  | { type: "snapshot"; item: Item }
  | { type: "sessionPatched"; session: Session };

const initialState: ConversationState = {
  session: null,
  items: [],
  lastSeq: 0,
  loading: true,
  loadError: null,
  turnError: null,
  usage: null,
  changesVersion: 0,
};

function upsert(items: Item[], item: Item): Item[] {
  const index = items.findIndex((existing) => existing.id === item.id);
  if (index < 0) {
    return [...items, item];
  }
  const next = items.slice();
  const current = items[index];
  const keepStreamedText =
    item.status === "in_progress" && (current?.text?.length ?? 0) > (item.text?.length ?? 0);
  next[index] = keepStreamedText ? { ...item, text: current?.text } : item;
  return next;
}

function applyEvent(state: ConversationState, { seq, event }: SessionEvent): ConversationState {
  if (seq <= state.lastSeq) {
    return state;
  }
  const next = { ...state, lastSeq: seq };
  switch (event.kind) {
    case "item.created":
    case "item.updated":
    case "item.completed":
      return { ...next, items: upsert(state.items, event.item as Item) };
    case "turn.started":
      return { ...next, turnError: null };
    case "turn.completed":
      return {
        ...next,
        turnError:
          event.reason === "failed" || event.reason === "interrupted"
            ? ((event.error as TurnError | null) ?? null)
            : null,
      };
    case "status.changed":
      return next.session
        ? { ...next, session: { ...next.session, status: event.status as Session["status"] } }
        : next;
    case "session.updated":
      return next.session
        ? {
            ...next,
            session: {
              ...next.session,
              title: (event.title as string) ?? next.session.title,
              mode: (event.mode as Session["mode"]) ?? next.session.mode,
              modelId: (event.modelId as string | null) ?? next.session.modelId,
              autoApprove:
                typeof event.autoApprove === "boolean"
                  ? event.autoApprove
                  : next.session.autoApprove,
              worktree:
                "worktree" in event
                  ? ((event.worktree as Session["worktree"]) ?? null)
                  : next.session.worktree,
            },
          }
        : next;
    case "usage.updated":
      return {
        ...next,
        usage: {
          contextTokens: Number(event.contextTokens ?? 0),
          contextWindow: (event.contextWindow as number | null) ?? null,
          totalInputTokens: Number(event.totalInputTokens ?? 0),
          totalOutputTokens: Number(event.totalOutputTokens ?? 0),
          estimatedCost: typeof event.estimatedCost === "number" ? event.estimatedCost : null,
        },
      };
    case "session.rewound": {
      const rewound = new Set((event.turnIds as string[] | undefined) ?? []);
      return {
        ...next,
        turnError: null,
        items: state.items.filter((item) => item.turnId === null || !rewound.has(item.turnId)),
      };
    }
    case "file.changed":
    case "file.reverted":
      return { ...next, changesVersion: state.changesVersion + 1 };
    default:
      return next;
  }
}

function reducer(state: ConversationState, action: Action): ConversationState {
  switch (action.type) {
    case "reset":
      return initialState;
    case "loaded":
      return {
        ...initialState,
        loading: false,
        session: action.session,
        items: action.items,
        lastSeq: action.session.lastSeq,
        usage: action.session.contextTokens
          ? {
              contextTokens: action.session.contextTokens,
              contextWindow: null,
              totalInputTokens: action.session.totalInputTokens,
              totalOutputTokens: action.session.totalOutputTokens,
              estimatedCost: action.session.estimatedCost,
            }
          : null,
      };
    case "failed":
      return { ...state, loading: false, loadError: action.error };
    case "event":
      return applyEvent(state, action.event);
    case "delta":
      return {
        ...state,
        items: state.items.map((item) =>
          item.id === action.itemId ? { ...item, text: (item.text ?? "") + action.delta } : item,
        ),
      };
    case "output":
      return {
        ...state,
        items: state.items.map((item) =>
          item.id === action.itemId
            ? { ...item, liveOutput: String(item.liveOutput ?? "") + action.delta }
            : item,
        ),
      };
    case "snapshot":
      return { ...state, items: upsert(state.items, action.item) };
    case "sessionPatched":
      return { ...state, session: action.session };
  }
}

export function useConversation(sessionId: string) {
  const [state, dispatch] = useReducer(reducer, initialState);

  useEffect(() => {
    let disposed = false;
    let stopListening: (() => void) | null = null;
    dispatch({ type: "reset" });

    void (async () => {
      try {
        const session = (await unwrap(
          api().GET("/api/v1/sessions/{sessionId}", { params: { path: { sessionId } } }),
        )) as Session;
        const items = (await unwrap(
          api().GET("/api/v1/sessions/{sessionId}/items", { params: { path: { sessionId } } }),
        )) as Item[];
        if (disposed) {
          return;
        }
        dispatch({ type: "loaded", session, items });
        stopListening = onRealtimeMessage((message) => {
          const payload = message.payload;
          if (payload.sessionId !== sessionId) {
            return;
          }
          if (message.type === "session.event") {
            dispatch({ type: "event", event: payload as unknown as SessionEvent });
          } else if (message.type === "session.delta" && payload.field === "text") {
            dispatch({
              type: "delta",
              itemId: String(payload.itemId),
              delta: String(payload.delta),
            });
          } else if (message.type === "session.delta" && payload.field === "output") {
            dispatch({
              type: "output",
              itemId: String(payload.itemId),
              delta: String(payload.delta),
            });
          } else if (message.type === "session.snapshot") {
            dispatch({ type: "snapshot", item: payload.item as Item });
          }
        });
        await core.subscribe(sessionId, session.lastSeq);
      } catch (error) {
        if (!disposed) {
          dispatch({ type: "failed", error: toAppError(error) });
        }
      }
    })();

    return () => {
      disposed = true;
      stopListening?.();
      void core.unsubscribe(sessionId).catch(() => undefined);
    };
  }, [sessionId]);

  return {
    state,
    patchSession: (session: Session) => dispatch({ type: "sessionPatched", session }),
  };
}
