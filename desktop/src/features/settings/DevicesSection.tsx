import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { LaptopIcon, PencilIcon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { queryKeys, useDevices } from "@/shared/api/queries";
import { toAppError, type Device } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Badge } from "@/shared/ui/badge";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";

const MAX_NAME_LENGTH = 100;

export function DevicesSection() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: devices } = useDevices();

  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.devices });

  const rename = async (device: Device, name: string) => {
    try {
      await unwrap(
        api().PATCH("/api/v1/devices/{deviceId}", {
          params: { path: { deviceId: device.id } },
          body: { name },
        }),
      );
      await refresh();
      toast.success(t("devices.renamed"));
      return true;
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
      return false;
    }
  };

  const signOut = async (device: Device) => {
    try {
      await unwrap(
        api().DELETE("/api/v1/devices/{deviceId}", { params: { path: { deviceId: device.id } } }),
      );
      toast.success(t("devices.signedOut", { name: device.name }));
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      await refresh();
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">{t("devices.description")}</p>
      {devices?.length === 0 && (
        <p className="text-sm text-muted-foreground">{t("devices.empty")}</p>
      )}
      <ul className="flex flex-col divide-y rounded-md border empty:hidden">
        {devices?.map((device) => (
          <DeviceRow
            key={device.id}
            device={device}
            onRename={(name) => rename(device, name)}
            onSignOut={() => void signOut(device)}
          />
        ))}
      </ul>
    </div>
  );
}

function DeviceRow({
  device,
  onRename,
  onSignOut,
}: {
  device: Device;
  onRename: (name: string) => Promise<boolean>;
  onSignOut: () => void;
}) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);

  return (
    <li className="flex flex-col gap-2 px-3 py-2.5">
      <div className="flex items-start gap-3">
        <LaptopIcon className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <div className="min-w-0 flex-1">
          {editing ? (
            <RenameForm
              initialName={device.name}
              onCancel={() => setEditing(false)}
              onSave={async (name) => {
                if (await onRename(name)) {
                  setEditing(false);
                }
              }}
            />
          ) : (
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="text-sm font-medium">{device.name}</span>
              {device.current && <Badge variant="secondary">{t("devices.current")}</Badge>}
              <DeviceDetails device={device} />
            </div>
          )}
          <Presence device={device} />
        </div>
        {!editing && (
          <Button
            size="icon-sm"
            variant="ghost"
            aria-label={t("devices.rename")}
            title={t("devices.rename")}
            onClick={() => setEditing(true)}
          >
            <PencilIcon />
          </Button>
        )}
        {!device.current && !confirming && (
          <Button size="sm" variant="outline" onClick={() => setConfirming(true)}>
            {t("devices.signOut")}
          </Button>
        )}
      </div>
      {confirming && (
        <div className="flex flex-wrap items-center justify-end gap-2 pl-7">
          <span className="mr-auto text-sm">
            {t("devices.confirmSignOut", { name: device.name })}
          </span>
          <Button size="sm" variant="ghost" onClick={() => setConfirming(false)}>
            {t("common.cancel")}
          </Button>
          <Button
            size="sm"
            variant="destructive"
            onClick={() => {
              setConfirming(false);
              onSignOut();
            }}
          >
            {t("devices.signOut")}
          </Button>
        </div>
      )}
    </li>
  );
}

function DeviceDetails({ device }: { device: Device }) {
  const os = typeof device.platform?.os === "string" ? device.platform.os : null;
  return (
    <>
      {os && <Badge variant="outline">{os}</Badge>}
      {device.clientVersion && <Badge variant="outline">v{device.clientVersion}</Badge>}
    </>
  );
}

function Presence({ device }: { device: Device }) {
  const { t, i18n } = useTranslation();
  const label = device.online
    ? t("devices.online")
    : device.lastSeenAt
      ? t("devices.lastSeen", {
          time: new Date(device.lastSeenAt).toLocaleString(i18n.language),
        })
      : t("devices.never");
  return (
    <p className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
      <span
        className={cn(
          "size-1.5 rounded-full",
          device.online ? "bg-emerald-500" : "bg-muted-foreground/40",
        )}
      />
      {label}
    </p>
  );
}

function RenameForm({
  initialName,
  onSave,
  onCancel,
}: {
  initialName: string;
  onSave: (name: string) => Promise<void>;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [name, setName] = useState(initialName);
  const [saving, setSaving] = useState(false);
  const trimmed = name.trim();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    await onSave(trimmed);
    setSaving(false);
  };

  return (
    <form className="flex gap-2" onSubmit={(event) => void submit(event)}>
      <Input
        autoFocus
        aria-label={t("devices.nameLabel")}
        required
        maxLength={MAX_NAME_LENGTH}
        value={name}
        onChange={(event) => setName(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.stopPropagation();
            onCancel();
          }
        }}
      />
      <Button
        type="submit"
        size="sm"
        variant="secondary"
        disabled={saving || trimmed === "" || trimmed === initialName}
      >
        {t("common.save")}
      </Button>
      <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
        {t("common.cancel")}
      </Button>
    </form>
  );
}
