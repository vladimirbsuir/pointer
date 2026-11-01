import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { useAuthStore } from "@/features/auth/auth-store";
import { api, problemToError } from "@/shared/api/client";
import { toAppError, type Me } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Button } from "@/shared/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/shared/ui/dialog";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/shared/ui/field";
import { Input } from "@/shared/ui/input";
import { Separator } from "@/shared/ui/separator";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/shared/ui/tabs";
import { AppearanceSwitcher } from "./AppearanceSwitcher";
import { InstructionsSection } from "./InstructionsSection";
import { McpSection } from "./McpSection";
import { PermissionsSection } from "./PermissionsSection";
import { ProvidersSection } from "./ProvidersSection";
import { ShortcutsList } from "./ShortcutsList";

type SettingsDialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

export function SettingsDialog({ open, onOpenChange }: SettingsDialogProps) {
  const { t } = useTranslation();
  const user = useAuthStore((state) => state.user);
  const setUser = useAuthStore((state) => state.setUser);

  if (!user) {
    return null;
  }

  const saveLocale = async (locale: string) => {
    const { data } = await api().PATCH("/api/v1/me", { body: { locale } });
    if (data) {
      setUser(data as Me);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t("settings.title")}</DialogTitle>
          <DialogDescription className="sr-only">{t("settings.title")}</DialogDescription>
        </DialogHeader>
        <Tabs defaultValue="account">
          <TabsList>
            <TabsTrigger value="account">{t("settings.account")}</TabsTrigger>
            <TabsTrigger value="providers">{t("settings.providers")}</TabsTrigger>
            <TabsTrigger value="instructions">{t("settings.instructions")}</TabsTrigger>
            <TabsTrigger value="permissions">{t("settings.permissions")}</TabsTrigger>
            <TabsTrigger value="mcp">MCP</TabsTrigger>
            <TabsTrigger value="appearance">{t("settings.appearance")}</TabsTrigger>
          </TabsList>
          <TabsContent value="account" className="flex flex-col gap-6 pt-4">
            <ProfileForm user={user} />
            <Separator />
            <GitHubSection user={user} />
            <Separator />
            <PasswordForm user={user} />
          </TabsContent>
          <TabsContent value="providers" className="pt-4">
            <ProvidersSection />
          </TabsContent>
          <TabsContent value="instructions" className="pt-4">
            <InstructionsSection />
          </TabsContent>
          <TabsContent value="permissions" className="pt-4">
            <PermissionsSection />
          </TabsContent>
          <TabsContent value="mcp" className="pt-4">
            <McpSection />
          </TabsContent>
          <TabsContent value="appearance" className="pt-4">
            <div className="flex flex-col gap-6">
              <AppearanceSwitcher onLanguageChange={(language) => void saveLocale(language)} />
              <Separator />
              <ShortcutsList />
            </div>
          </TabsContent>
        </Tabs>
      </DialogContent>
    </Dialog>
  );
}

function ProfileForm({ user }: { user: Me }) {
  const { t } = useTranslation();
  const setUser = useAuthStore((state) => state.setUser);
  const [displayName, setDisplayName] = useState(user.displayName);
  const [saving, setSaving] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    const { data, error, response } = await api().PATCH("/api/v1/me", {
      body: { displayName: displayName.trim() },
    });
    setSaving(false);
    if (data) {
      setUser(data as Me);
      toast.success(t("settings.profileSaved"));
    } else {
      toast.error(errorMessage(t, problemToError(error, response.status)));
    }
  };

  return (
    <form onSubmit={(event) => void submit(event)}>
      <FieldGroup>
        <Field>
          <FieldLabel htmlFor="settings-email">{t("settings.email")}</FieldLabel>
          <Input id="settings-email" value={user.email} disabled />
        </Field>
        <Field>
          <FieldLabel htmlFor="settings-name">{t("settings.displayName")}</FieldLabel>
          <div className="flex gap-2">
            <Input
              id="settings-name"
              required
              maxLength={100}
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
            />
            <Button
              type="submit"
              variant="secondary"
              disabled={saving || displayName.trim() === user.displayName}
            >
              {t("common.save")}
            </Button>
          </div>
        </Field>
      </FieldGroup>
    </form>
  );
}

function GitHubSection({ user }: { user: Me }) {
  const { t } = useTranslation();
  const linkGitHub = useAuthStore((state) => state.linkGitHub);
  const [linking, setLinking] = useState(false);

  const link = async () => {
    setLinking(true);
    try {
      await linkGitHub();
      toast.success(t("settings.githubLinkedToast"));
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setLinking(false);
    }
  };

  return (
    <div className="flex items-center justify-between gap-4">
      <div>
        <p className="text-sm font-medium">{t("settings.github")}</p>
        <p className="text-sm text-muted-foreground">
          {user.github
            ? t("settings.githubLinked", { login: user.github.login })
            : t("settings.githubNotLinked")}
        </p>
      </div>
      {!user.github && (
        <Button variant="outline" disabled={linking} onClick={() => void link()}>
          {linking ? t("auth.waitingForGitHub") : t("settings.linkGitHub")}
        </Button>
      )}
    </div>
  );
}

function PasswordForm({ user }: { user: Me }) {
  const { t } = useTranslation();
  const setUser = useAuthStore((state) => state.setUser);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (newPassword !== confirmPassword) {
      setError(t("settings.passwordMismatch"));
      return;
    }
    setSaving(true);
    setError(null);
    const { error: problem, response } = await api().PUT("/api/v1/auth/password", {
      body: {
        currentPassword: user.hasPassword ? currentPassword : undefined,
        newPassword,
      },
    });
    setSaving(false);
    if (response.ok) {
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setUser({ ...user, hasPassword: true });
      toast.success(t("settings.passwordChanged"));
    } else {
      setError(errorMessage(t, problemToError(problem, response.status)));
    }
  };

  return (
    <form onSubmit={(event) => void submit(event)}>
      <FieldGroup>
        <div>
          <p className="text-sm font-medium">{t("settings.password")}</p>
          {!user.hasPassword && (
            <FieldDescription>{t("settings.setPasswordDescription")}</FieldDescription>
          )}
        </div>
        {user.hasPassword && (
          <Field>
            <FieldLabel htmlFor="current-password">{t("settings.currentPassword")}</FieldLabel>
            <Input
              id="current-password"
              type="password"
              autoComplete="current-password"
              required
              value={currentPassword}
              onChange={(event) => setCurrentPassword(event.target.value)}
            />
          </Field>
        )}
        <Field>
          <FieldLabel htmlFor="new-password">{t("settings.newPassword")}</FieldLabel>
          <Input
            id="new-password"
            type="password"
            autoComplete="new-password"
            required
            minLength={8}
            maxLength={128}
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
          />
          <FieldDescription>{t("auth.passwordHint")}</FieldDescription>
        </Field>
        <Field>
          <FieldLabel htmlFor="confirm-password">{t("settings.confirmPassword")}</FieldLabel>
          <Input
            id="confirm-password"
            type="password"
            autoComplete="new-password"
            required
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
          />
        </Field>
        {error && <FieldError>{error}</FieldError>}
        <Button type="submit" variant="secondary" disabled={saving} className="self-start">
          {user.hasPassword ? t("settings.changePassword") : t("settings.setPassword")}
        </Button>
      </FieldGroup>
    </form>
  );
}
