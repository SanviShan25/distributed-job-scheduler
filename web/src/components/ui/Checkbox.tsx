import { useId, type InputHTMLAttributes, type ReactNode } from "react";

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "type"> {
  label: ReactNode;
  hint?: ReactNode;
}

export function Checkbox({ label, hint, id, ...rest }: CheckboxProps) {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const hintId = `${inputId}-hint`;
  return (
    <div className="flex items-start gap-3">
      <input
        id={inputId}
        type="checkbox"
        aria-describedby={hint ? hintId : undefined}
        className="mt-0.5 size-5 shrink-0 rounded border-line-strong accent-brand-600"
        {...rest}
      />
      <div className="flex flex-col">
        <label htmlFor={inputId} className="font-medium text-ink">
          {label}
        </label>
        {hint && (
          <span id={hintId} className="text-sm text-ink-muted">
            {hint}
          </span>
        )}
      </div>
    </div>
  );
}
