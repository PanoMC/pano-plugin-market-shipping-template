<div class="market-shipping-example-shipping-note" data-method-id={methodId}>
  <p class="market-shipping-example-shipping-note__text mb-0 text-body-secondary">{$_('descriptor.name')}</p>
</div>

<script module>
  // A slot view: `view.slot` puts it into the market's checkout, under the shipping method list (a carrier's pickup
  // point picker starts here). The market renders the slot with { quote, methodId, address, onchange } and shows every
  // item, so compare `methodId` first when this carrier owns only some of the methods.
  export const view = { slot: 'market:checkout:shipping', id: "example" };
</script>

<script>
  import { plugin } from '@panomc/sdk/controllers';

  const { _ } = plugin('market-shipping-example');

  /** id of the chosen shipping method (market props of the slot: quote, methodId, address, onchange). */
  let { methodId = '' } = $props();
</script>
