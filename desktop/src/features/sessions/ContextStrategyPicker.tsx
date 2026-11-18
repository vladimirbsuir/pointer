import { useTranslation } from "react-i18next";
import { ChevronDownIcon, LayersIcon } from "lucide-react";
import { CONTEXT_STRATEGIES, type ContextStrategy } from "@/shared/api/types";
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

type ContextStrategyPickerProps = {
  value: ContextStrategy;
  onChange: (strategy: ContextStrategy) => void;
  compact?: boolean;
};

export function ContextStrategyPicker({ value, onChange, compact }: ContextStrategyPickerProps) {
  const { t } = useTranslation();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            variant="ghost"
            size="sm"
            className="gap-1.5 text-muted-foreground"
            title={t("contextStrategy.hint")}
          />
        }
      >
        <LayersIcon />
        <span className={compact ? "sr-only" : undefined}>
          {t(`contextStrategy.options.${value}`)}
        </span>
        <ChevronDownIcon className="opacity-60" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80">
        <DropdownMenuGroup>
          <DropdownMenuLabel className="text-xs text-muted-foreground">
            {t("contextStrategy.title")}
          </DropdownMenuLabel>
          <DropdownMenuRadioGroup
            value={value}
            onValueChange={(next) => onChange(next as ContextStrategy)}
          >
            {CONTEXT_STRATEGIES.map((strategy) => (
              <DropdownMenuRadioItem key={strategy} value={strategy}>
                <span className="flex flex-col gap-0.5">
                  <span>{t(`contextStrategy.options.${strategy}`)}</span>
                  <span className="text-xs text-muted-foreground">
                    {t(`contextStrategy.descriptions.${strategy}`)}
                  </span>
                </span>
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
