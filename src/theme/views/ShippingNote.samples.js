// The note has no data of its own: it only shows the name of the carrier for the chosen method.
export const notApplicable = ['empty', 'loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { label: 'A method of this carrier is chosen', props: { methodId: 'example' } },
};
