import { Eye, EyeOff } from "lucide-react";
import { useState } from "react";
import { Input, type InputProps } from "./Input";

export type PasswordInputProps = Omit<InputProps, "type" | "trailing">;

/** A password field with a "Show password" toggle, so people can check what they typed. */
export function PasswordInput(props: PasswordInputProps) {
  const [visible, setVisible] = useState(false);
  const Icon = visible ? EyeOff : Eye;
  return (
    <Input
      {...props}
      type={visible ? "text" : "password"}
      autoCapitalize="none"
      autoCorrect="off"
      spellCheck={false}
      trailing={
        <button
          type="button"
          aria-pressed={visible}
          onClick={() => {
            setVisible((value) => !value);
          }}
          className="inline-flex size-10 items-center justify-center rounded-md text-ink-muted hover:bg-canvas hover:text-ink"
        >
          <Icon aria-hidden="true" className="size-5" />
          <span className="sr-only">Show password</span>
        </button>
      }
    />
  );
}
