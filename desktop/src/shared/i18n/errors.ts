import type { TFunction } from "i18next";
import type { AppError } from "@/shared/api/types";
import en from "./locales/en.json";

type ErrorCode = keyof typeof en.errors;

function isKnownCode(code: string): code is ErrorCode {
  return code in en.errors;
}

export function errorMessage(t: TFunction, error: AppError): string {
  return isKnownCode(error.code) ? t(`errors.${error.code}`) : t("errors.unknown");
}
