/** Joins class strings, dropping falsy values. (No tailwind-merge: recipes are composed so they do not conflict.) */
export const cn = (...parts: (string | false | null | undefined)[]): string => parts.filter(Boolean).join(' ');
