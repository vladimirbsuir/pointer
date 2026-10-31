import { useTranslation } from "react-i18next";
import { ChevronDownIcon, CpuIcon } from "lucide-react";
import { enabledModels, useProviders } from "@/shared/api/queries";
import { Button } from "@/shared/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/shared/ui/dropdown-menu";

type ModelPickerProps = {
  value: string | null;
  onChange: (modelId: string) => void;
};

export function ModelPicker({ value, onChange }: ModelPickerProps) {
  const { t } = useTranslation();
  const { data: providers } = useProviders();
  const options = enabledModels(providers);
  const current = options.find((option) => option.model.id === value);
  const groups = (providers ?? []).filter((provider) =>
    provider.models.some((model) => model.enabled),
  );

  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="ghost" size="sm" className="gap-1.5" />}>
        <CpuIcon />
        <span className="max-w-48 truncate">
          {current ? current.model.displayName : t("chat.chooseModel")}
        </span>
        <ChevronDownIcon className="opacity-60" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-72">
        {groups.length === 0 && (
          <DropdownMenuGroup>
            <DropdownMenuLabel>{t("chat.noModels")}</DropdownMenuLabel>
          </DropdownMenuGroup>
        )}
        <DropdownMenuRadioGroup
          value={value ?? ""}
          onValueChange={(next) => onChange(String(next))}
        >
          {groups.map((provider) => (
            <DropdownMenuGroup key={provider.id}>
              <DropdownMenuLabel className="text-xs text-muted-foreground">
                {provider.name}
              </DropdownMenuLabel>
              {provider.models
                .filter((model) => model.enabled)
                .map((model) => (
                  <DropdownMenuRadioItem key={model.id} value={model.id}>
                    <span className="truncate">{model.displayName}</span>
                  </DropdownMenuRadioItem>
                ))}
            </DropdownMenuGroup>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
