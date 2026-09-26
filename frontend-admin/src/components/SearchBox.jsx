import { useEffect, useState } from "react";

// Search input that reports its value after the user pauses typing.
export default function SearchBox({ placeholder, onSearch, delay = 300 }) {
  const [value, setValue] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => onSearch(value.trim()), delay);
    return () => clearTimeout(timer);
  }, [value]);
  return (
    <input
      type="search"
      className="border p-2 rounded w-full max-w-sm"
      placeholder={placeholder}
      aria-label={placeholder}
      value={value}
      onChange={(e) => setValue(e.target.value)}
    />
  );
}
