import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { toAppError, type AppError } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Button } from "@/shared/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/shared/ui/card";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/shared/ui/field";
import { Input } from "@/shared/ui/input";
import { Separator } from "@/shared/ui/separator";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/shared/ui/tabs";
import { AppearanceSwitcher } from "@/features/settings/AppearanceSwitcher";
import { useAuthStore } from "./auth-store";

type Mode = "signIn" | "register";

export function AuthScreen() {
  const { t } = useTranslation();
  const signIn = useAuthStore((state) => state.signIn);
  const register = useAuthStore((state) => state.register);
  const signInWithGitHub = useAuthStore((state) => state.signInWithGitHub);
  const [mode, setMode] = useState<Mode>("signIn");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [pending, setPending] = useState<"form" | "github" | null>(null);
  const [error, setError] = useState<AppError | null>(null);

  const run = async (kind: "form" | "github", action: () => Promise<void>) => {
    setPending(kind);
    setError(null);
    try {
      await action();
    } catch (caught) {
      setError(toAppError(caught));
      setPending(null);
    }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void run("form", () =>
      mode === "signIn"
        ? signIn(email.trim(), password)
        : register(email.trim(), password, displayName.trim()),
    );
  };

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-6 bg-muted/30 p-6">
      <div className="text-center">
        <h1 className="text-3xl font-semibold tracking-tight">{t("app.name")}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{t("app.tagline")}</p>
      </div>
      <Card className="w-full max-w-sm">
        <CardHeader>
          <Tabs
            value={mode}
            onValueChange={(value) => {
              setMode(value as Mode);
              setError(null);
            }}
          >
            <TabsList className="w-full">
              <TabsTrigger value="signIn">{t("auth.signInTab")}</TabsTrigger>
              <TabsTrigger value="register">{t("auth.registerTab")}</TabsTrigger>
            </TabsList>
            <TabsContent value="signIn" />
            <TabsContent value="register" />
          </Tabs>
          <CardTitle className="sr-only">
            {mode === "signIn" ? t("auth.signIn") : t("auth.register")}
          </CardTitle>
          <CardDescription className="sr-only">{t("app.tagline")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <form onSubmit={submit}>
            <FieldGroup>
              {mode === "register" && (
                <Field>
                  <FieldLabel htmlFor="displayName">{t("auth.displayName")}</FieldLabel>
                  <Input
                    id="displayName"
                    autoComplete="name"
                    required
                    maxLength={100}
                    value={displayName}
                    onChange={(event) => setDisplayName(event.target.value)}
                  />
                </Field>
              )}
              <Field>
                <FieldLabel htmlFor="email">{t("auth.email")}</FieldLabel>
                <Input
                  id="email"
                  type="email"
                  autoComplete="email"
                  required
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                />
              </Field>
              <Field>
                <FieldLabel htmlFor="password">{t("auth.password")}</FieldLabel>
                <Input
                  id="password"
                  type="password"
                  autoComplete={mode === "signIn" ? "current-password" : "new-password"}
                  required
                  minLength={mode === "register" ? 8 : undefined}
                  maxLength={128}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                />
                {mode === "register" && (
                  <FieldDescription>{t("auth.passwordHint")}</FieldDescription>
                )}
              </Field>
              {error && <FieldError>{errorMessage(t, error)}</FieldError>}
              <Button type="submit" disabled={pending !== null}>
                {mode === "signIn" ? t("auth.signIn") : t("auth.register")}
              </Button>
            </FieldGroup>
          </form>
          <div className="flex items-center gap-3 text-xs text-muted-foreground">
            <Separator className="flex-1" />
            {t("common.or")}
            <Separator className="flex-1" />
          </div>
          <Button
            variant="outline"
            disabled={pending !== null}
            onClick={() => void run("github", signInWithGitHub)}
          >
            {pending === "github" ? t("auth.waitingForGitHub") : t("auth.continueWithGitHub")}
          </Button>
        </CardContent>
      </Card>
      <AppearanceSwitcher compact />
    </main>
  );
}
